/*-
 * ========================LICENSE_START=================================
 * O-RAN-SC
 * %%
 * Copyright (C) 2019 Nordix Foundation
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ========================LICENSE_END===================================
 */

package org.oransc.policyagent.tasks;

import java.util.Vector;

import org.oransc.policyagent.clients.A1Client;
import org.oransc.policyagent.clients.AsyncRestClient;
import org.oransc.policyagent.exceptions.ServiceException;
import org.oransc.policyagent.repository.ImmutablePolicyType;
import org.oransc.policyagent.repository.Policies;
import org.oransc.policyagent.repository.Policy;
import org.oransc.policyagent.repository.PolicyType;
import org.oransc.policyagent.repository.PolicyTypes;
import org.oransc.policyagent.repository.Ric;
import org.oransc.policyagent.repository.Service;
import org.oransc.policyagent.repository.Services;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Recovery handling of RIC, which means:
 * - load all policy types
 * - send all policy instances to the RIC
 * --- if that fails remove all policy instances
 * - Notify subscribing services
 */
public class RicRecoveryTask {

    private static final Logger logger = LoggerFactory.getLogger(RicRecoveryTask.class);

    private final A1Client a1Client;
    private final PolicyTypes policyTypes;
    private final Policies policies;
    private final Services services;

    public RicRecoveryTask(A1Client a1Client, PolicyTypes policyTypes, Policies policies, Services services) {
        this.a1Client = a1Client;
        this.policyTypes = policyTypes;
        this.policies = policies;
        this.services = services;
    }

    public void run(Ric ric) {
        logger.debug("Handling ric: {}", ric.getConfig().name());

        synchronized (ric) {
            if (ric.state().equals(Ric.RicState.RECOVERING)) {
                return; // Already running
            }
            ric.setState(Ric.RicState.RECOVERING);
        }
        Flux<PolicyType> recoverTypes = recoverPolicyTypes(ric);
        Flux<?> deletePoliciesInRic = deleteAllPoliciesInRic(ric);
        Flux<?> recreatePoliciesInRic = recreateAllPoliciesInRic(ric);

        Flux.concat(recoverTypes, deletePoliciesInRic, recreatePoliciesInRic) //
            .subscribe(x -> logger.debug("Recover: " + x), //
                throwable -> onRecoveryError(ric, throwable), //
                () -> onRecoveryComplete(ric));
    }

    private void onRecoveryComplete(Ric ric) {
        logger.debug("Recovery completed for:" + ric.name());
        ric.setState(Ric.RicState.IDLE);
        notifyAllServices("Recovery completed for:" + ric.name());
    }

    private void notifyAllServices(String body) {
        synchronized (services) {
            for (Service service : services.getAll()) {
                String url = service.getCallbackUrl();
                if (service.getCallbackUrl().length() > 0) {
                    createClient(url) //
                        .put("", body) //
                        .subscribe(rsp -> logger.debug("Service called"),
                            throwable -> logger.warn("Service called failed", throwable),
                            () -> logger.debug("Service called complete"));
                }
            }
        }
    }

    private void onRecoveryError(Ric ric, Throwable t) {
        logger.warn("Recovery failed for: {}, reason: {}", ric.name(), t.getMessage());

        // If recovery fails, try to remove all instances
        deleteAllPolicies(ric);
        Flux<PolicyType> recoverTypes = recoverPolicyTypes(ric);
        Flux<?> deletePoliciesInRic = deleteAllPoliciesInRic(ric);

        Flux.merge(recoverTypes, deletePoliciesInRic) //
            .subscribe(x -> logger.debug("Brute recover: " + x), //
                throwable -> onRemoveAllError(ric, throwable), //
                () -> onRecoveryComplete(ric));
    }

    private void onRemoveAllError(Ric ric, Throwable t) {
        logger.warn("Remove all failed for: {}, reason: {}", ric.name(), t.getMessage());
        ric.setState(Ric.RicState.UNDEFINED);
    }

    private AsyncRestClient createClient(final String url) {
        return new AsyncRestClient(url);
    }

    private Flux<PolicyType> recoverPolicyTypes(Ric ric) {
        ric.clearSupportedPolicyTypes();
        return a1Client.getPolicyTypeIdentities(ric.getConfig().baseUrl()) //
            .flatMapMany(types -> Flux.fromIterable(types)) //
            .doOnNext(typeId -> logger.debug("For ric: {}, handling type: {}", ric.getConfig().name(), typeId)) //
            .flatMap((policyTypeId) -> getPolicyType(ric, policyTypeId)) //
            .doOnNext(policyType -> ric.addSupportedPolicyType(policyType)); //
    }

    private Mono<PolicyType> getPolicyType(Ric ric, String policyTypeId) {
        if (policyTypes.contains(policyTypeId)) {
            try {
                return Mono.just(policyTypes.getType(policyTypeId));
            } catch (ServiceException e) {
                return Mono.error(e);
            }
        }
        return a1Client.getPolicyType(ric.getConfig().baseUrl(), policyTypeId) //
            .flatMap(schema -> createPolicyType(policyTypeId, schema));
    }

    private Mono<PolicyType> createPolicyType(String policyTypeId, String schema) {
        PolicyType pt = ImmutablePolicyType.builder().name(policyTypeId).schema(schema).build();
        policyTypes.put(pt);
        return Mono.just(pt);
    }

    private void deleteAllPolicies(Ric ric) {
        synchronized (policies) {
            for (Policy policy : policies.getForRic(ric.name())) {
                this.policies.remove(policy);
            }
        }
    }

    private Flux<String> deleteAllPoliciesInRic(Ric ric) {
        return a1Client.getPolicyIdentities(ric.getConfig().baseUrl()) //
            .flatMapMany(policyIds -> Flux.fromIterable(policyIds)) //
            .doOnNext(policyId -> logger.debug("Deleting policy: {}, for ric: {}", policyId, ric.getConfig().name()))
            .flatMap(policyId -> a1Client.deletePolicy(ric.getConfig().baseUrl(), policyId)); //
    }

    private Flux<String> recreateAllPoliciesInRic(Ric ric) {
        synchronized (policies) {
            return Flux.fromIterable(new Vector<>(policies.getForRic(ric.name()))) //
                .doOnNext(
                    policy -> logger.debug("Recreating policy: {}, for ric: {}", policy.id(), ric.getConfig().name()))
                .flatMap(policy -> a1Client.putPolicy(policy));
        }
    }

}