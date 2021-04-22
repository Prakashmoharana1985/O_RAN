/*-
 * ========================LICENSE_START=================================
 * O-RAN-SC
 * %%
 * Copyright (C) 2020 Nordix Foundation
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

package org.oransc.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.oransc.enrichment.clients.AsyncRestClient;
import org.oransc.enrichment.clients.AsyncRestClientFactory;
import org.oransc.enrichment.configuration.ApplicationConfig;
import org.oransc.enrichment.configuration.ImmutableHttpProxyConfig;
import org.oransc.enrichment.configuration.ImmutableWebClientConfig;
import org.oransc.enrichment.configuration.WebClientConfig;
import org.oransc.enrichment.configuration.WebClientConfig.HttpProxyConfig;
import org.oransc.enrichment.controller.ConsumerSimulatorController;
import org.oransc.enrichment.controller.ProducerSimulatorController;
import org.oransc.enrichment.controllers.a1e.A1eConsts;
import org.oransc.enrichment.controllers.a1e.A1eEiJobInfo;
import org.oransc.enrichment.controllers.a1e.A1eEiJobStatus;
import org.oransc.enrichment.controllers.a1e.A1eEiTypeInfo;
import org.oransc.enrichment.controllers.r1consumer.ConsumerConsts;
import org.oransc.enrichment.controllers.r1consumer.ConsumerInfoTypeInfo;
import org.oransc.enrichment.controllers.r1consumer.ConsumerJobInfo;
import org.oransc.enrichment.controllers.r1producer.ProducerCallbacks;
import org.oransc.enrichment.controllers.r1producer.ProducerConsts;
import org.oransc.enrichment.controllers.r1producer.ProducerEiTypeInfo;
import org.oransc.enrichment.controllers.r1producer.ProducerJobInfo;
import org.oransc.enrichment.controllers.r1producer.ProducerRegistrationInfo;
import org.oransc.enrichment.controllers.r1producer.ProducerStatusInfo;
import org.oransc.enrichment.exceptions.ServiceException;
import org.oransc.enrichment.repository.EiJob;
import org.oransc.enrichment.repository.EiJobs;
import org.oransc.enrichment.repository.EiProducer;
import org.oransc.enrichment.repository.EiProducers;
import org.oransc.enrichment.repository.EiType;
import org.oransc.enrichment.repository.EiTypes;
import org.oransc.enrichment.tasks.ProducerSupervision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = { //
        "server.ssl.key-store=./config/keystore.jks", //
        "app.webclient.trust-store=./config/truststore.jks", //
        "app.vardata-directory=./target"})
class ApplicationTest {
    private final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final String TYPE_ID = "typeId";
    private final String PRODUCER_ID = "producerId";
    private final String EI_JOB_PROPERTY = "\"property1\"";
    private final String EI_JOB_ID = "jobId";

    @Autowired
    ApplicationContext context;

    @Autowired
    EiJobs eiJobs;

    @Autowired
    EiTypes eiTypes;

    @Autowired
    EiProducers eiProducers;

    @Autowired
    ApplicationConfig applicationConfig;

    @Autowired
    ProducerSimulatorController producerSimulator;

    @Autowired
    ConsumerSimulatorController consumerSimulator;

    @Autowired
    ProducerSupervision producerSupervision;

    @Autowired
    ProducerCallbacks producerCallbacks;

    private static Gson gson = new GsonBuilder().create();

    /**
     * Overrides the BeanFactory.
     */
    @TestConfiguration
    static class TestBeanFactory {
        @Bean
        public ServletWebServerFactory servletContainer() {
            return new TomcatServletWebServerFactory();
        }
    }

    @LocalServerPort
    private int port;

    @BeforeEach
    void reset() {
        this.eiJobs.clear();
        this.eiTypes.clear();
        this.eiProducers.clear();
        this.producerSimulator.getTestResults().reset();
        this.consumerSimulator.getTestResults().reset();
    }

    @AfterEach
    void check() {
        assertThat(this.producerSimulator.getTestResults().errorFound).isFalse();
    }

    @Test
    void generateApiDoc() throws FileNotFoundException {
        String url = "/v3/api-docs";
        ResponseEntity<String> resp = restClient().getForEntity(url).block();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        JSONObject jsonObj = new JSONObject(resp.getBody());
        assertThat(jsonObj.remove("servers")).isNotNull();

        String indented = jsonObj.toString(4);
        try (PrintStream out = new PrintStream(new FileOutputStream("api/ecs-api.json"))) {
            out.print(indented);
        }
    }

    @Test
    void a1eGetEiTypes() throws Exception {
        putEiProducerWithOneType(PRODUCER_ID, "test");
        String url = A1eConsts.API_ROOT + "/eitypes";
        String rsp = restClient().get(url).block();
        assertThat(rsp).isEqualTo("[\"test\"]");
    }

    @Test
    void consumerGetEiTypes() throws Exception {
        putEiProducerWithOneType(PRODUCER_ID, "test");
        String url = ConsumerConsts.API_ROOT + "/info-types";
        String rsp = restClient().get(url).block();
        assertThat(rsp).isEqualTo("[\"test\"]");
    }

    @Test
    void a1eGetEiTypesEmpty() throws Exception {
        String url = A1eConsts.API_ROOT + "/eitypes";
        String rsp = restClient().get(url).block();
        assertThat(rsp).isEqualTo("[]");
    }

    @Test
    void consumerGetEiTypesEmpty() throws Exception {
        String url = ConsumerConsts.API_ROOT + "/info-types";
        String rsp = restClient().get(url).block();
        assertThat(rsp).isEqualTo("[]");
    }

    @Test
    void a1eGetEiType() throws Exception {
        putEiProducerWithOneType(PRODUCER_ID, "test");
        String url = A1eConsts.API_ROOT + "/eitypes/test";
        String rsp = restClient().get(url).block();
        A1eEiTypeInfo info = gson.fromJson(rsp, A1eEiTypeInfo.class);
        assertThat(info).isNotNull();
    }

    @Test
    void consumerGetEiType() throws Exception {
        putEiProducerWithOneType(PRODUCER_ID, "test");
        String url = ConsumerConsts.API_ROOT + "/info-types/test";
        String rsp = restClient().get(url).block();
        ConsumerInfoTypeInfo info = gson.fromJson(rsp, ConsumerInfoTypeInfo.class);
        assertThat(info).isNotNull();
        assertThat(info.jobDataSchema).isNotNull();
    }

    @Test
    void a1eGetEiTypeNotFound() throws Exception {
        String url = A1eConsts.API_ROOT + "/eitypes/junk";
        testErrorCode(restClient().get(url), HttpStatus.NOT_FOUND, "Information type not found: junk");
    }

    @Test
    void consumerGetEiTypeNotFound() throws Exception {
        String url = ConsumerConsts.API_ROOT + "/info-types/junk";
        testErrorCode(restClient().get(url), HttpStatus.NOT_FOUND, "Information type not found: junk");
    }

    @Test
    void a1eGetEiJobsIds() throws Exception {
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);
        putEiJob(TYPE_ID, "jobId");
        final String JOB_ID_JSON = "[\"jobId\"]";
        String url = A1eConsts.API_ROOT + "/eijobs?eiTypeId=typeId";
        String rsp = restClient().get(url).block();
        assertThat(rsp).isEqualTo(JOB_ID_JSON);

        url = A1eConsts.API_ROOT + "/eijobs?owner=owner";
        rsp = restClient().get(url).block();
        assertThat(rsp).isEqualTo(JOB_ID_JSON);

        url = A1eConsts.API_ROOT + "/eijobs?owner=JUNK";
        rsp = restClient().get(url).block();
        assertThat(rsp).isEqualTo("[]");

        url = A1eConsts.API_ROOT + "/eijobs";
        rsp = restClient().get(url).block();
        assertThat(rsp).isEqualTo(JOB_ID_JSON);

        url = A1eConsts.API_ROOT + "/eijobs?eiTypeId=typeId&&owner=owner";
        rsp = restClient().get(url).block();
        assertThat(rsp).isEqualTo(JOB_ID_JSON);

        url = A1eConsts.API_ROOT + "/eijobs?eiTypeId=JUNK";
        rsp = restClient().get(url).block();
        assertThat(rsp).isEqualTo("[]");
    }

    @Test
    void consumerGetInformationJobsIds() throws Exception {
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);
        putEiJob(TYPE_ID, "jobId");
        final String JOB_ID_JSON = "[\"jobId\"]";
        String url = ConsumerConsts.API_ROOT + "/info-jobs?infoTypeId=typeId";
        String rsp = restClient().get(url).block();
        assertThat(rsp).isEqualTo(JOB_ID_JSON);

        url = ConsumerConsts.API_ROOT + "/info-jobs?owner=owner";
        rsp = restClient().get(url).block();
        assertThat(rsp).isEqualTo(JOB_ID_JSON);

        url = ConsumerConsts.API_ROOT + "/info-jobs?owner=JUNK";
        rsp = restClient().get(url).block();
        assertThat(rsp).isEqualTo("[]");

        url = ConsumerConsts.API_ROOT + "/info-jobs";
        rsp = restClient().get(url).block();
        assertThat(rsp).isEqualTo(JOB_ID_JSON);

        url = ConsumerConsts.API_ROOT + "/info-jobs?infoTypeId=typeId&&owner=owner";
        rsp = restClient().get(url).block();
        assertThat(rsp).isEqualTo(JOB_ID_JSON);

        url = ConsumerConsts.API_ROOT + "/info-jobs?infoTypeId=JUNK";
        rsp = restClient().get(url).block();
        assertThat(rsp).isEqualTo("[]");
    }

    @Test
    void a1eGetEiJob() throws Exception {
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);
        putEiJob(TYPE_ID, "jobId");
        String url = A1eConsts.API_ROOT + "/eijobs/jobId";
        String rsp = restClient().get(url).block();
        A1eEiJobInfo info = gson.fromJson(rsp, A1eEiJobInfo.class);
        assertThat(info.owner).isEqualTo("owner");
        assertThat(info.eiTypeId).isEqualTo(TYPE_ID);
    }

    @Test
    void consumerGetEiJob() throws Exception {
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);
        putEiJob(TYPE_ID, "jobId");
        String url = ConsumerConsts.API_ROOT + "/info-jobs/jobId";
        String rsp = restClient().get(url).block();
        ConsumerJobInfo info = gson.fromJson(rsp, ConsumerJobInfo.class);
        assertThat(info.owner).isEqualTo("owner");
        assertThat(info.infoTypeId).isEqualTo(TYPE_ID);
    }

    @Test
    void a1eGetEiJobNotFound() throws Exception {
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);
        String url = A1eConsts.API_ROOT + "/eijobs/junk";
        testErrorCode(restClient().get(url), HttpStatus.NOT_FOUND, "Could not find Information job: junk");
    }

    @Test
    void consumerGetInfoJobNotFound() throws Exception {
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);
        String url = ConsumerConsts.API_ROOT + "/info-jobs/junk";
        testErrorCode(restClient().get(url), HttpStatus.NOT_FOUND, "Could not find Information job: junk");
    }

    @Test
    void a1eGetEiJobStatus() throws Exception {
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);
        putEiJob(TYPE_ID, "jobId");

        verifyJobStatus("jobId", "ENABLED");
    }

    @Test
    void consumerGetEiJobStatus() throws Exception {
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);
        putEiJob(TYPE_ID, "jobId");

        String url = ConsumerConsts.API_ROOT + "/info-jobs/jobId/status";
        String rsp = restClient().get(url).block();
        assertThat(rsp).contains("ENABLED");
    }

    @Test
    void a1eDeleteEiJob() throws Exception {
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);
        putEiJob(TYPE_ID, "jobId");
        assertThat(this.eiJobs.size()).isEqualTo(1);
        String url = A1eConsts.API_ROOT + "/eijobs/jobId";
        restClient().delete(url).block();
        assertThat(this.eiJobs.size()).isZero();

        ProducerSimulatorController.TestResults simulatorResults = this.producerSimulator.getTestResults();
        await().untilAsserted(() -> assertThat(simulatorResults.jobsStopped.size()).isEqualTo(1));
        assertThat(simulatorResults.jobsStopped.get(0)).isEqualTo("jobId");
    }

    @Test
    void consumerDeleteEiJob() throws Exception {
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);
        putEiJob(TYPE_ID, "jobId");
        assertThat(this.eiJobs.size()).isEqualTo(1);
        String url = ConsumerConsts.API_ROOT + "/info-jobs/jobId";
        restClient().delete(url).block();
        assertThat(this.eiJobs.size()).isZero();

        ProducerSimulatorController.TestResults simulatorResults = this.producerSimulator.getTestResults();
        await().untilAsserted(() -> assertThat(simulatorResults.jobsStopped.size()).isEqualTo(1));
        assertThat(simulatorResults.jobsStopped.get(0)).isEqualTo("jobId");
    }

    @Test
    void a1eDeleteEiJobNotFound() throws Exception {
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);
        String url = A1eConsts.API_ROOT + "/eijobs/junk";
        testErrorCode(restClient().get(url), HttpStatus.NOT_FOUND, "Could not find Information job: junk");
    }

    @Test
    void consumerDeleteEiJobNotFound() throws Exception {
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);
        String url = ConsumerConsts.API_ROOT + "/info-jobs/junk";
        testErrorCode(restClient().get(url), HttpStatus.NOT_FOUND, "Could not find Information job: junk");
    }

    @Test
    void a1ePutEiJob() throws Exception {
        // Test that one producer accepting a job is enough
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);
        putEiProducerWithOneTypeRejecting("simulateProducerError", TYPE_ID);

        String url = A1eConsts.API_ROOT + "/eijobs/jobId";
        String body = gson.toJson(eiJobInfo());
        ResponseEntity<String> resp = restClient().putForEntity(url, body).block();
        assertThat(this.eiJobs.size()).isEqualTo(1);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ProducerSimulatorController.TestResults simulatorResults = this.producerSimulator.getTestResults();
        await().untilAsserted(() -> assertThat(simulatorResults.jobsStarted.size()).isEqualTo(1));
        ProducerJobInfo request = simulatorResults.jobsStarted.get(0);
        assertThat(request.id).isEqualTo("jobId");

        // One retry --> two calls
        await().untilAsserted(() -> assertThat(simulatorResults.noOfRejectedCreate).isEqualTo(2));
        assertThat(simulatorResults.noOfRejectedCreate).isEqualTo(2);

        resp = restClient().putForEntity(url, body).block();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        EiJob job = this.eiJobs.getJob("jobId");
        assertThat(job.getOwner()).isEqualTo("owner");

        verifyJobStatus(EI_JOB_ID, "ENABLED");
    }

    @Test
    void consumerPutInformationJob() throws Exception {
        // Test that one producer accepting a job is enough
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);

        String url = ConsumerConsts.API_ROOT + "/info-jobs/jobId";
        String body = gson.toJson(consumerJobInfo());
        ResponseEntity<String> resp = restClient().putForEntity(url, body).block();
        assertThat(this.eiJobs.size()).isEqualTo(1);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ProducerSimulatorController.TestResults simulatorResults = this.producerSimulator.getTestResults();
        await().untilAsserted(() -> assertThat(simulatorResults.jobsStarted.size()).isEqualTo(1));
        ProducerJobInfo request = simulatorResults.jobsStarted.get(0);
        assertThat(request.id).isEqualTo("jobId");

        resp = restClient().putForEntity(url, body).block();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        EiJob job = this.eiJobs.getJob("jobId");
        assertThat(job.getOwner()).isEqualTo("owner");

        verifyJobStatus(EI_JOB_ID, "ENABLED");
    }

    @Test
    void consumerPutInformationJob_noType() throws JsonMappingException, JsonProcessingException, ServiceException {
        String url = ConsumerConsts.API_ROOT + "/info-jobs/jobId?typeCheck=false";
        String body = gson.toJson(consumerJobInfo());
        ResponseEntity<String> resp = restClient().putForEntity(url, body).block();
        assertThat(this.eiJobs.size()).isEqualTo(1);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verifyJobStatus(EI_JOB_ID, "DISABLED");

        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);

        verifyJobStatus(EI_JOB_ID, "ENABLED");

    }

    @Test
    void a1ePutEiJob_jsonSchemavalidationError() throws Exception {
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);

        String url = A1eConsts.API_ROOT + "/eijobs/jobId";
        // The element with name "property1" is mandatory in the schema
        A1eEiJobInfo jobInfo = new A1eEiJobInfo("typeId", jsonObject("{ \"XXstring\" : \"value\" }"), "owner",
            "targetUri", "jobStatusUrl");
        String body = gson.toJson(jobInfo);

        testErrorCode(restClient().put(url, body), HttpStatus.CONFLICT, "Json validation failure");
    }

    @Test
    void consumerPutJob_jsonSchemavalidationError() throws Exception {
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);

        String url = ConsumerConsts.API_ROOT + "/info-jobs/jobId?typeCheck=true";
        // The element with name "property1" is mandatory in the schema
        ConsumerJobInfo jobInfo = new ConsumerJobInfo("typeId", jsonObject("{ \"XXstring\" : \"value\" }"), "owner",
            "targetUri", "jobStatusUrl");
        String body = gson.toJson(jobInfo);

        testErrorCode(restClient().put(url, body), HttpStatus.CONFLICT, "Json validation failure");
    }

    @Test
    void a1eChangingEiTypeGetRejected() throws Exception {
        putEiProducerWithOneType("producer1", "typeId1");
        putEiProducerWithOneType("producer2", "typeId2");
        putEiJob("typeId1", "jobId");

        String url = A1eConsts.API_ROOT + "/eijobs/jobId";
        String body = gson.toJson(eiJobInfo("typeId2", "jobId"));
        testErrorCode(restClient().put(url, body), HttpStatus.CONFLICT,
            "Not allowed to change type for existing EI job");
    }

    @Test
    void consumerChangingInfoTypeGetRejected() throws Exception {
        putEiProducerWithOneType("producer1", "typeId1");
        putEiProducerWithOneType("producer2", "typeId2");
        putEiJob("typeId1", "jobId");

        String url = ConsumerConsts.API_ROOT + "/info-jobs/jobId";
        String body = gson.toJson(consumerJobInfo("typeId2", "jobId"));
        testErrorCode(restClient().put(url, body), HttpStatus.CONFLICT, "Not allowed to change type for existing job");
    }

    @Test
    void producerPutEiType() throws JsonMappingException, JsonProcessingException, ServiceException {
        assertThat(putEiType(TYPE_ID)).isEqualTo(HttpStatus.CREATED);
        assertThat(putEiType(TYPE_ID)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void producerPutEiType_noSchema() {
        String url = ProducerConsts.API_ROOT + "/eitypes/" + TYPE_ID;
        String body = "{}";
        testErrorCode(restClient().put(url, body), HttpStatus.BAD_REQUEST, "No schema provided");
    }

    @Test
    void producerDeleteEiType() throws Exception {
        putEiType(TYPE_ID);
        String url = ProducerConsts.API_ROOT + "/eitypes/" + TYPE_ID;
        restClient().delete(url).block();
        assertThat(this.eiTypes.size()).isEqualTo(0);

        testErrorCode(restClient().delete(url), HttpStatus.NOT_FOUND, "Information type not found");
    }

    @Test
    void producerDeleteEiTypeExistingProducer() throws Exception {
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);
        String url = ProducerConsts.API_ROOT + "/eitypes/" + TYPE_ID;
        testErrorCode(restClient().delete(url), HttpStatus.NOT_ACCEPTABLE,
            "The type has active producers: " + PRODUCER_ID);
        assertThat(this.eiTypes.size()).isEqualTo(1);
    }

    @Test
    void producerPutProducerWithOneType_rejecting()
        throws JsonMappingException, JsonProcessingException, ServiceException {
        putEiProducerWithOneTypeRejecting("simulateProducerError", TYPE_ID);
        String url = A1eConsts.API_ROOT + "/eijobs/" + EI_JOB_ID;
        String body = gson.toJson(eiJobInfo());
        restClient().put(url, body).block();

        ProducerSimulatorController.TestResults simulatorResults = this.producerSimulator.getTestResults();
        // There is one retry -> 2 calls
        await().untilAsserted(() -> assertThat(simulatorResults.noOfRejectedCreate).isEqualTo(2));
        assertThat(simulatorResults.noOfRejectedCreate).isEqualTo(2);

        verifyJobStatus(EI_JOB_ID, "DISABLED");
    }

    @Test
    void producerGetEiProducerTypes() throws Exception {
        final String EI_TYPE_ID_2 = TYPE_ID + "_2";
        putEiProducerWithOneType("producer1", TYPE_ID);
        putEiJob(TYPE_ID, "jobId");
        putEiProducerWithOneType("producer2", EI_TYPE_ID_2);
        putEiJob(EI_TYPE_ID_2, "jobId2");
        String url = ProducerConsts.API_ROOT + "/eitypes";

        ResponseEntity<String> resp = restClient().getForEntity(url).block();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains(TYPE_ID);
        assertThat(resp.getBody()).contains(EI_TYPE_ID_2);
    }

    @Test
    void producerPutEiProducer() throws Exception {
        this.putEiType(TYPE_ID);
        String url = ProducerConsts.API_ROOT + "/eiproducers/eiProducerId";
        String body = gson.toJson(producerEiRegistratioInfo(TYPE_ID));

        ResponseEntity<String> resp = restClient().putForEntity(url, body).block();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(this.eiTypes.size()).isEqualTo(1);
        assertThat(this.eiProducers.getProducersForType(TYPE_ID).size()).isEqualTo(1);
        assertThat(this.eiProducers.size()).isEqualTo(1);
        assertThat(this.eiProducers.get("eiProducerId").getEiTypes().iterator().next().getId()).isEqualTo(TYPE_ID);

        resp = restClient().putForEntity(url, body).block();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        resp = restClient().getForEntity(url).block();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo(body);
    }

    @Test
    void producerPutEiProducerExistingJob() throws Exception {
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);
        putEiJob(TYPE_ID, "jobId");
        String url = ProducerConsts.API_ROOT + "/eiproducers/eiProducerId";
        String body = gson.toJson(producerEiRegistratioInfo(TYPE_ID));
        restClient().putForEntity(url, body).block();

        ProducerSimulatorController.TestResults simulatorResults = this.producerSimulator.getTestResults();
        await().untilAsserted(() -> assertThat(simulatorResults.jobsStarted.size()).isEqualTo(2));
        ProducerJobInfo request = simulatorResults.jobsStarted.get(0);
        assertThat(request.id).isEqualTo("jobId");
    }

    @Test
    void testPutEiProducer_noType() throws Exception {
        String url = ProducerConsts.API_ROOT + "/eiproducers/eiProducerId";
        String body = gson.toJson(producerEiRegistratioInfo(TYPE_ID));
        testErrorCode(restClient().put(url, body), HttpStatus.NOT_FOUND, "Information type not found");
    }

    @Test
    void producerPutProducerAndEiJob() throws Exception {
        this.putEiType(TYPE_ID);
        String url = ProducerConsts.API_ROOT + "/eiproducers/eiProducerId";
        String body = gson.toJson(producerEiRegistratioInfo(TYPE_ID));
        restClient().putForEntity(url, body).block();
        assertThat(this.eiTypes.size()).isEqualTo(1);
        this.eiTypes.getType(TYPE_ID);

        url = A1eConsts.API_ROOT + "/eijobs/jobId";
        body = gson.toJson(eiJobInfo());
        restClient().putForEntity(url, body).block();

        ProducerSimulatorController.TestResults simulatorResults = this.producerSimulator.getTestResults();
        await().untilAsserted(() -> assertThat(simulatorResults.jobsStarted.size()).isEqualTo(1));
        ProducerJobInfo request = simulatorResults.jobsStarted.get(0);
        assertThat(request.id).isEqualTo("jobId");
    }

    @Test
    void producerGetEiJobsForProducer() throws JsonMappingException, JsonProcessingException, ServiceException {
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);
        putEiJob(TYPE_ID, "jobId1");
        putEiJob(TYPE_ID, "jobId2");

        // PUT a consumerRestApiTestBase.java
        String url = ProducerConsts.API_ROOT + "/eiproducers/eiProducerId";
        String body = gson.toJson(producerEiRegistratioInfo(TYPE_ID));
        restClient().putForEntity(url, body).block();

        url = ProducerConsts.API_ROOT + "/eiproducers/eiProducerId/eijobs";
        ResponseEntity<String> resp = restClient().getForEntity(url).block();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ProducerJobInfo[] parsedResp = gson.fromJson(resp.getBody(), ProducerJobInfo[].class);
        assertThat(parsedResp[0].typeId).isEqualTo(TYPE_ID);
        assertThat(parsedResp[1].typeId).isEqualTo(TYPE_ID);
    }

    @Test
    void producerDeleteEiProducer() throws Exception {
        putEiProducerWithOneType("eiProducerId", TYPE_ID);
        putEiProducerWithOneType("eiProducerId2", TYPE_ID);

        assertThat(this.eiProducers.size()).isEqualTo(2);
        EiType type = this.eiTypes.getType(TYPE_ID);
        assertThat(this.eiProducers.getProducerIdsForType(type.getId())).contains("eiProducerId");
        assertThat(this.eiProducers.getProducerIdsForType(type.getId())).contains("eiProducerId2");
        putEiJob(TYPE_ID, "jobId");
        assertThat(this.eiJobs.size()).isEqualTo(1);

        deleteEiProducer("eiProducerId");
        assertThat(this.eiProducers.size()).isEqualTo(1);
        assertThat(this.eiProducers.getProducerIdsForType(TYPE_ID)).doesNotContain("eiProducerId");
        verifyJobStatus("jobId", "ENABLED");

        deleteEiProducer("eiProducerId2");
        assertThat(this.eiProducers.size()).isZero();
        assertThat(this.eiTypes.size()).isEqualTo(1);
        verifyJobStatus("jobId", "DISABLED");
    }

    @Test
    void a1eJobStatusNotifications() throws JsonMappingException, JsonProcessingException, ServiceException {
        ConsumerSimulatorController.TestResults consumerCalls = this.consumerSimulator.getTestResults();
        ProducerSimulatorController.TestResults producerCalls = this.producerSimulator.getTestResults();

        putEiProducerWithOneType("eiProducerId", TYPE_ID);
        putEiJob(TYPE_ID, "jobId");
        putEiProducerWithOneType("eiProducerId2", TYPE_ID);
        await().untilAsserted(() -> assertThat(producerCalls.jobsStarted.size()).isEqualTo(2));

        deleteEiProducer("eiProducerId2");
        assertThat(this.eiTypes.size()).isEqualTo(1); // The type remains, one producer left
        deleteEiProducer("eiProducerId");
        assertThat(this.eiTypes.size()).isEqualTo(1); // The type remains
        assertThat(this.eiJobs.size()).isEqualTo(1); // The job remains
        await().untilAsserted(() -> assertThat(consumerCalls.status.size()).isEqualTo(1));
        assertThat(consumerCalls.status.get(0).state).isEqualTo(A1eEiJobStatus.EiJobStatusValues.DISABLED);

        putEiProducerWithOneType("eiProducerId", TYPE_ID);
        await().untilAsserted(() -> assertThat(consumerCalls.status.size()).isEqualTo(2));
        assertThat(consumerCalls.status.get(1).state).isEqualTo(A1eEiJobStatus.EiJobStatusValues.ENABLED);
    }

    @Test
    void a1eJobStatusNotifications2() throws JsonMappingException, JsonProcessingException, ServiceException {
        // Test replacing a producer with new and removed types

        // Create a job
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);
        putEiJob(TYPE_ID, EI_JOB_ID);

        // change the type for the producer, the job shall be disabled
        putEiProducerWithOneType(PRODUCER_ID, "junk");
        verifyJobStatus(EI_JOB_ID, "DISABLED");
        ConsumerSimulatorController.TestResults consumerCalls = this.consumerSimulator.getTestResults();
        await().untilAsserted(() -> assertThat(consumerCalls.status.size()).isEqualTo(1));
        assertThat(consumerCalls.status.get(0).state).isEqualTo(A1eEiJobStatus.EiJobStatusValues.DISABLED);

        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);
        verifyJobStatus(EI_JOB_ID, "ENABLED");
        await().untilAsserted(() -> assertThat(consumerCalls.status.size()).isEqualTo(2));
        assertThat(consumerCalls.status.get(1).state).isEqualTo(A1eEiJobStatus.EiJobStatusValues.ENABLED);
    }

    @Test
    void producerGetProducerEiType() throws JsonMappingException, JsonProcessingException, ServiceException {
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);
        String url = ProducerConsts.API_ROOT + "/eitypes/" + TYPE_ID;
        ResponseEntity<String> resp = restClient().getForEntity(url).block();
        ProducerEiTypeInfo info = gson.fromJson(resp.getBody(), ProducerEiTypeInfo.class);
        assertThat(info.jobDataSchema).isNotNull();
    }

    @Test
    void producerGetProducerIdentifiers() throws JsonMappingException, JsonProcessingException, ServiceException {
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);
        String url = ProducerConsts.API_ROOT + "/eiproducers";
        ResponseEntity<String> resp = restClient().getForEntity(url).block();
        assertThat(resp.getBody()).contains(PRODUCER_ID);

        url = ProducerConsts.API_ROOT + "/eiproducers?ei_type_id=" + TYPE_ID;
        resp = restClient().getForEntity(url).block();
        assertThat(resp.getBody()).contains(PRODUCER_ID);

        url = ProducerConsts.API_ROOT + "/eiproducers?ei_type_id=junk";
        resp = restClient().getForEntity(url).block();
        assertThat(resp.getBody()).isEqualTo("[]");
    }

    @Test
    void producerSupervision() throws JsonMappingException, JsonProcessingException, ServiceException {

        ConsumerSimulatorController.TestResults consumerResults = this.consumerSimulator.getTestResults();
        putEiProducerWithOneTypeRejecting("simulateProducerError", TYPE_ID);

        {
            // Create a job
            putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);
            putEiJob(TYPE_ID, EI_JOB_ID);
            verifyJobStatus(EI_JOB_ID, "ENABLED");
            deleteEiProducer(PRODUCER_ID);
            // A Job disabled status notification shall now be received
            await().untilAsserted(() -> assertThat(consumerResults.status.size()).isEqualTo(1));
            assertThat(consumerResults.status.get(0).state).isEqualTo(A1eEiJobStatus.EiJobStatusValues.DISABLED);
            verifyJobStatus(EI_JOB_ID, "DISABLED");
        }

        assertThat(this.eiProducers.size()).isEqualTo(1);
        assertThat(this.eiTypes.size()).isEqualTo(1);
        assertProducerOpState("simulateProducerError", ProducerStatusInfo.OperationalState.ENABLED);

        this.producerSupervision.createTask().blockLast();
        this.producerSupervision.createTask().blockLast();

        // Now we have one producer that is disabled
        assertThat(this.eiProducers.size()).isEqualTo(1);
        assertProducerOpState("simulateProducerError", ProducerStatusInfo.OperationalState.DISABLED);

        // After 3 failed checks, the producer shall be deregisterred
        this.producerSupervision.createTask().blockLast();
        assertThat(this.eiProducers.size()).isEqualTo(0); // The producer is removed
        assertThat(this.eiTypes.size()).isEqualTo(1); // The type remains

        // Now we have one disabled job, and no producer.
        // PUT a producer, then a Job ENABLED status notification shall be received
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);
        await().untilAsserted(() -> assertThat(consumerResults.status.size()).isEqualTo(2));
        assertThat(consumerResults.status.get(1).state).isEqualTo(A1eEiJobStatus.EiJobStatusValues.ENABLED);
        verifyJobStatus(EI_JOB_ID, "ENABLED");
    }

    @Test
    void producerSupervision2() throws JsonMappingException, JsonProcessingException, ServiceException {
        // Test that supervision enables not enabled jobs and sends a notification when
        // suceeded

        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);
        putEiJob(TYPE_ID, EI_JOB_ID);

        EiProducer producer = this.eiProducers.getProducer(PRODUCER_ID);
        EiJob job = this.eiJobs.getJob(EI_JOB_ID);
        // Pretend that the producer did reject the job and the a DISABLED notification
        // is sent for the job
        producer.setJobDisabled(job);
        job.setLastReportedStatus(false);
        verifyJobStatus(EI_JOB_ID, "DISABLED");

        // Run the supervision and wait for the job to get started in the producer
        this.producerSupervision.createTask().blockLast();
        ConsumerSimulatorController.TestResults consumerResults = this.consumerSimulator.getTestResults();
        await().untilAsserted(() -> assertThat(consumerResults.status.size()).isEqualTo(1));
        assertThat(consumerResults.status.get(0).state).isEqualTo(A1eEiJobStatus.EiJobStatusValues.ENABLED);
        verifyJobStatus(EI_JOB_ID, "ENABLED");
    }

    @Test
    void testGetStatus() throws JsonMappingException, JsonProcessingException, ServiceException {
        putEiProducerWithOneTypeRejecting("simulateProducerError", TYPE_ID);
        putEiProducerWithOneTypeRejecting("simulateProducerError2", TYPE_ID);

        String url = "/status";
        ResponseEntity<String> resp = restClient().getForEntity(url).block();
        assertThat(resp.getBody()).contains("hunky dory");
    }

    @Test
    void testEiJobDatabase() throws Exception {
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);
        putEiJob(TYPE_ID, "jobId1");
        putEiJob(TYPE_ID, "jobId2");

        assertThat(this.eiJobs.size()).isEqualTo(2);

        {
            EiJob savedJob = this.eiJobs.getJob("jobId1");
            // Restore the jobs
            EiJobs jobs = new EiJobs(this.applicationConfig, this.producerCallbacks);
            jobs.restoreJobsFromDatabase();
            assertThat(jobs.size()).isEqualTo(2);
            EiJob restoredJob = jobs.getJob("jobId1");
            assertThat(restoredJob.getId()).isEqualTo("jobId1");
            assertThat(restoredJob.getLastUpdated()).isEqualTo(savedJob.getLastUpdated());

            jobs.remove("jobId1", this.eiProducers);
            jobs.remove("jobId2", this.eiProducers);
        }
        {
            // Restore the jobs, no jobs in database
            EiJobs jobs = new EiJobs(this.applicationConfig, this.producerCallbacks);
            jobs.restoreJobsFromDatabase();
            assertThat(jobs.size()).isEqualTo(0);
        }
        logger.warn("Test removing a job when the db file is gone");
        this.eiJobs.remove("jobId1", this.eiProducers);
        assertThat(this.eiJobs.size()).isEqualTo(1);

        ProducerSimulatorController.TestResults simulatorResults = this.producerSimulator.getTestResults();
        await().untilAsserted(() -> assertThat(simulatorResults.jobsStopped.size()).isEqualTo(3));
    }

    @Test
    void testEiTypesDatabase() throws Exception {
        putEiProducerWithOneType(PRODUCER_ID, TYPE_ID);

        assertThat(this.eiTypes.size()).isEqualTo(1);

        {
            // Restore the types
            EiTypes types = new EiTypes(this.applicationConfig);
            types.restoreTypesFromDatabase();
            assertThat(types.size()).isEqualTo(1);

        }
        {
            // Restore the jobs, no jobs in database
            EiTypes types = new EiTypes(this.applicationConfig);
            types.clear();
            types.restoreTypesFromDatabase();
            assertThat(types.size()).isEqualTo(0);
        }
        logger.warn("Test removing a job when the db file is gone");
        this.eiTypes.remove(this.eiTypes.getType(TYPE_ID));
        assertThat(this.eiJobs.size()).isEqualTo(0);
    }

    private void deleteEiProducer(String eiProducerId) {
        String url = ProducerConsts.API_ROOT + "/eiproducers/" + eiProducerId;
        restClient().deleteForEntity(url).block();
    }

    private void verifyJobStatus(String jobId, String expStatus) {
        String url = A1eConsts.API_ROOT + "/eijobs/" + jobId + "/status";
        String rsp = restClient().get(url).block();
        assertThat(rsp).contains(expStatus);
    }

    private void assertProducerOpState(String producerId,
        ProducerStatusInfo.OperationalState expectedOperationalState) {
        String statusUrl = ProducerConsts.API_ROOT + "/eiproducers/" + producerId + "/status";
        ResponseEntity<String> resp = restClient().getForEntity(statusUrl).block();
        ProducerStatusInfo statusInfo = gson.fromJson(resp.getBody(), ProducerStatusInfo.class);
        assertThat(statusInfo.opState).isEqualTo(expectedOperationalState);
    }

    ProducerEiTypeInfo producerEiTypeRegistrationInfo(String typeId)
        throws JsonMappingException, JsonProcessingException {
        return new ProducerEiTypeInfo(jsonSchemaObject());
    }

    ProducerRegistrationInfo producerEiRegistratioInfoRejecting(String typeId)
        throws JsonMappingException, JsonProcessingException {
        return new ProducerRegistrationInfo(Arrays.asList(typeId), //
            baseUrl() + ProducerSimulatorController.JOB_ERROR_URL,
            baseUrl() + ProducerSimulatorController.SUPERVISION_ERROR_URL);
    }

    ProducerRegistrationInfo producerEiRegistratioInfo(String typeId)
        throws JsonMappingException, JsonProcessingException {
        return new ProducerRegistrationInfo(Arrays.asList(typeId), //
            baseUrl() + ProducerSimulatorController.JOB_URL, baseUrl() + ProducerSimulatorController.SUPERVISION_URL);
    }

    private ConsumerJobInfo consumerJobInfo() throws JsonMappingException, JsonProcessingException {
        return consumerJobInfo(TYPE_ID, EI_JOB_ID);
    }

    ConsumerJobInfo consumerJobInfo(String typeId, String eiJobId)
        throws JsonMappingException, JsonProcessingException {
        return new ConsumerJobInfo(typeId, jsonObject(), "owner", "targetUri",
            baseUrl() + ConsumerSimulatorController.getJobStatusUrl(eiJobId));
    }

    private A1eEiJobInfo eiJobInfo() throws JsonMappingException, JsonProcessingException {
        return eiJobInfo(TYPE_ID, EI_JOB_ID);
    }

    A1eEiJobInfo eiJobInfo(String typeId, String eiJobId) throws JsonMappingException, JsonProcessingException {
        return new A1eEiJobInfo(typeId, jsonObject(), "owner", "targetUri",
            baseUrl() + ConsumerSimulatorController.getJobStatusUrl(eiJobId));
    }

    private Object jsonObject(String json) {
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new NullPointerException(e.toString());
        }
    }

    private Object jsonSchemaObject() {
        // a json schema with one mandatory property named "string"
        String schemaStr = "{" //
            + "\"$schema\": \"http://json-schema.org/draft-04/schema#\"," //
            + "\"type\": \"object\"," //
            + "\"properties\": {" //
            + EI_JOB_PROPERTY + " : {" //
            + "    \"type\": \"string\"" //
            + "  }" //
            + "}," //
            + "\"required\": [" //
            + EI_JOB_PROPERTY //
            + "]" //
            + "}"; //
        return jsonObject(schemaStr);
    }

    private Object jsonObject() {
        return jsonObject("{ " + EI_JOB_PROPERTY + " : \"value\" }");
    }

    private EiJob putEiJob(String eiTypeId, String jobId)
        throws JsonMappingException, JsonProcessingException, ServiceException {

        String url = A1eConsts.API_ROOT + "/eijobs/" + jobId;
        String body = gson.toJson(eiJobInfo(eiTypeId, jobId));
        restClient().putForEntity(url, body).block();

        return this.eiJobs.getJob(jobId);
    }

    private HttpStatus putEiType(String eiTypeId)
        throws JsonMappingException, JsonProcessingException, ServiceException {
        String url = ProducerConsts.API_ROOT + "/eitypes/" + eiTypeId;
        String body = gson.toJson(producerEiTypeRegistrationInfo(eiTypeId));

        ResponseEntity<String> resp = restClient().putForEntity(url, body).block();
        this.eiTypes.getType(eiTypeId);
        return resp.getStatusCode();

    }

    private EiType putEiProducerWithOneTypeRejecting(String producerId, String eiTypeId)
        throws JsonMappingException, JsonProcessingException, ServiceException {
        this.putEiType(eiTypeId);
        String url = ProducerConsts.API_ROOT + "/eiproducers/" + producerId;
        String body = gson.toJson(producerEiRegistratioInfoRejecting(eiTypeId));
        restClient().putForEntity(url, body).block();
        return this.eiTypes.getType(eiTypeId);
    }

    private EiType putEiProducerWithOneType(String producerId, String eiTypeId)
        throws JsonMappingException, JsonProcessingException, ServiceException {
        this.putEiType(eiTypeId);

        String url = ProducerConsts.API_ROOT + "/eiproducers/" + producerId;
        String body = gson.toJson(producerEiRegistratioInfo(eiTypeId));

        restClient().putForEntity(url, body).block();

        return this.eiTypes.getType(eiTypeId);
    }

    private String baseUrl() {
        return "https://localhost:" + this.port;
    }

    private AsyncRestClient restClient(boolean useTrustValidation) {
        WebClientConfig config = this.applicationConfig.getWebClientConfig();
        HttpProxyConfig httpProxyConfig = ImmutableHttpProxyConfig.builder() //
            .httpProxyHost("") //
            .httpProxyPort(0) //
            .build();
        config = ImmutableWebClientConfig.builder() //
            .keyStoreType(config.keyStoreType()) //
            .keyStorePassword(config.keyStorePassword()) //
            .keyStore(config.keyStore()) //
            .keyPassword(config.keyPassword()) //
            .isTrustStoreUsed(useTrustValidation) //
            .trustStore(config.trustStore()) //
            .trustStorePassword(config.trustStorePassword()) //
            .httpProxyConfig(httpProxyConfig).build();

        AsyncRestClientFactory restClientFactory = new AsyncRestClientFactory(config);
        return restClientFactory.createRestClientNoHttpProxy(baseUrl());
    }

    private AsyncRestClient restClient() {
        return restClient(false);
    }

    private void testErrorCode(Mono<?> request, HttpStatus expStatus, String responseContains) {
        testErrorCode(request, expStatus, responseContains, true);
    }

    private void testErrorCode(Mono<?> request, HttpStatus expStatus, String responseContains,
        boolean expectApplicationProblemJsonMediaType) {
        StepVerifier.create(request) //
            .expectSubscription() //
            .expectErrorMatches(
                t -> checkWebClientError(t, expStatus, responseContains, expectApplicationProblemJsonMediaType)) //
            .verify();
    }

    private boolean checkWebClientError(Throwable throwable, HttpStatus expStatus, String responseContains,
        boolean expectApplicationProblemJsonMediaType) {
        assertTrue(throwable instanceof WebClientResponseException);
        WebClientResponseException responseException = (WebClientResponseException) throwable;
        assertThat(responseException.getStatusCode()).isEqualTo(expStatus);
        assertThat(responseException.getResponseBodyAsString()).contains(responseContains);
        if (expectApplicationProblemJsonMediaType) {
            assertThat(responseException.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        }
        return true;
    }

}
