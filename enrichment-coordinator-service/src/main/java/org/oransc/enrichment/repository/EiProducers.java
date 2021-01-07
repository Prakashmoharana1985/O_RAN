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

package org.oransc.enrichment.repository;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

import org.immutables.value.Value.Immutable;
import org.oransc.enrichment.controllers.consumer.ConsumerCallbacks;
import org.oransc.enrichment.controllers.producer.ProducerCallbacks;
import org.oransc.enrichment.exceptions.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Dynamic representation of all EiProducers.
 */
@SuppressWarnings("squid:S2629") // Invoke method(s) only conditionally
@Component
public class EiProducers {
    private final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private final Map<String, EiProducer> allEiProducers = new HashMap<>();
    private final MultiMap<EiProducer> producersByType = new MultiMap<>();

    @Autowired
    private ProducerCallbacks producerCallbacks;

    @Autowired
    private ConsumerCallbacks consumerCallbacks;

    @Autowired
    private EiTypes eiTypes;

    @Autowired
    private EiJobs eiJobs;

    @Immutable
    public interface EiTypeRegistrationInfo {
        String id();

        Object jobDataSchema();
    }

    @Immutable
    public interface EiProducerRegistrationInfo {
        String id();

        Collection<EiTypeRegistrationInfo> supportedTypes();

        String jobCallbackUrl();

        String producerSupervisionCallbackUrl();

    }

    public EiProducer registerProducer(EiProducerRegistrationInfo producerInfo) {
        final String producerId = producerInfo.id();
        EiProducer previousDefinition = this.get(producerId);
        if (previousDefinition != null) {
            for (EiType type : previousDefinition.getEiTypes()) {
                producersByType.remove(type.getId(), producerId);
            }
            allEiProducers.remove(producerId);
        }

        EiProducer producer = createProducer(producerInfo);
        allEiProducers.put(producer.getId(), producer);
        for (EiType type : producer.getEiTypes()) {
            producersByType.put(type.getId(), producer.getId(), producer);
        }

        if (previousDefinition != null) {
            purgeTypes(previousDefinition.getEiTypes());
            this.consumerCallbacks.notifyConsumersProducerDeleted(previousDefinition);
        }

        producerCallbacks.restartEiJobs(producer, this.eiJobs);
        consumerCallbacks.notifyConsumersProducerAdded(producer);
        return producer;
    }

    private void purgeTypes(Collection<EiType> types) {
        for (EiType type : types) {
            if (getProducersForType(type.getId()).isEmpty()) {
                this.eiTypes.remove(type);
            }
        }
    }

    private EiType getType(EiTypeRegistrationInfo typeInfo) {
        EiType type = this.eiTypes.get(typeInfo.id());
        if (type == null) {
            type = new EiType(typeInfo.id(), typeInfo.jobDataSchema());
            this.eiTypes.put(type);
            this.consumerCallbacks.notifyConsumersTypeAdded(type);
        }
        return type;
    }

    private EiProducer createProducer(EiProducerRegistrationInfo producerInfo) {
        ArrayList<EiType> types = new ArrayList<>();

        EiProducer producer = new EiProducer(producerInfo.id(), types, producerInfo.jobCallbackUrl(),
            producerInfo.producerSupervisionCallbackUrl());

        for (EiTypeRegistrationInfo typeInfo : producerInfo.supportedTypes()) {
            EiType type = getType(typeInfo);
            types.add(type);
        }
        return producer;
    }

    public synchronized Collection<EiProducer> getAllProducers() {
        return new Vector<>(allEiProducers.values());
    }

    public synchronized EiProducer getProducer(String id) throws ServiceException {
        EiProducer p = allEiProducers.get(id);
        if (p == null) {
            throw new ServiceException("Could not find EI producer: " + id);
        }
        return p;
    }

    public synchronized EiProducer get(String id) {
        return allEiProducers.get(id);
    }

    public synchronized int size() {
        return allEiProducers.size();
    }

    public synchronized void clear() {
        this.allEiProducers.clear();
        this.producersByType.clear();
    }

    public void deregisterProducer(EiProducer producer, EiTypes eiTypes) {
        allEiProducers.remove(producer.getId());
        for (EiType type : producer.getEiTypes()) {
            if (producersByType.remove(type.getId(), producer.getId()) == null) {
                this.logger.error("Bug, no producer found");
            }
            if (this.producersByType.get(type.getId()).isEmpty()) {
                eiTypes.remove(type);
            }
        }
        this.consumerCallbacks.notifyConsumersProducerDeleted(producer);
    }

    public synchronized Collection<EiProducer> getProducersForType(EiType type) {
        return this.producersByType.get(type.getId());
    }

    public synchronized Collection<EiProducer> getProducersForType(String typeId) {
        return this.producersByType.get(typeId);
    }

    public synchronized Collection<String> getProducerIdsForType(String typeId) {
        Collection<String> producerIds = new ArrayList<>();
        for (EiProducer p : this.getProducersForType(typeId)) {
            producerIds.add(p.getId());
        }
        return producerIds;
    }

}
