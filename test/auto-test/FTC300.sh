#!/bin/bash

#  ============LICENSE_START===============================================
#  Copyright (C) 2020 Nordix Foundation. All rights reserved.
#  ========================================================================
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
#  ============LICENSE_END=================================================
#

TC_ONELINE_DESCR="Resync 10000 policies using OSC and STD interface"

#App names to include in the test when running docker, space separated list
DOCKER_INCLUDED_IMAGES="CBS CONSUL CP CR MR PA RICSIM SDNC NGW"

#App names to include in the test when running kubernetes, space separated list
KUBE_INCLUDED_IMAGES="CP CR MR PA RICSIM SDNC KUBEPROXY NGW"
#Prestarted app (not started by script) to include in the test when running kubernetes, space separated list
KUBE_PRESTARTED_IMAGES=""

#Ignore image in DOCKER_INCLUDED_IMAGES, KUBE_INCLUDED_IMAGES if
#the image is not configured in the supplied env_file
#Used for images not applicable to all supported profile
CONDITIONALLY_IGNORED_IMAGES="NGW"

#Supported test environment profiles
SUPPORTED_PROFILES="ONAP-GUILIN ONAP-HONOLULU ONAP-ISTANBUL ORAN-CHERRY ORAN-D-RELEASE ORAN-E-RELEASE"
#Supported run modes
SUPPORTED_RUNMODES="DOCKER KUBE"

. ../common/testcase_common.sh  $@
. ../common/agent_api_functions.sh
. ../common/consul_cbs_functions.sh
. ../common/control_panel_api_functions.sh
. ../common/controller_api_functions.sh
. ../common/cr_api_functions.sh
. ../common/mr_api_functions.sh
. ../common/ricsimulator_api_functions.sh
. ../common/kube_proxy_api_functions.sh
. ../common/gateway_api_functions.sh

setup_testenvironment

#### TEST BEGIN ####

generate_policy_uuid

# Tested variants of REST/DMAAP/SDNC config
TESTED_VARIANTS="REST   DMAAP   REST+SDNC   DMAAP+SDNC DMAAP_BATCH DMAAP_BATCH+SDNC"
#Test agent and simulator protocol versions (others are http only)
TESTED_PROTOCOLS="HTTP HTTPS"

for __httpx in $TESTED_PROTOCOLS ; do
    for interface in $TESTED_VARIANTS ; do

        echo "#####################################################################"
        echo "#####################################################################"
        echo "### Testing agent: "$interface" and "$__httpx
        echo "#####################################################################"
        echo "#####################################################################"

        if [ $__httpx == "HTTPS" ]; then
            use_cr_https
            use_simulator_https
            use_mr_https
            if [[ $interface = *"SDNC"* ]]; then
                use_sdnc_https
            fi
            if [[ $interface = *"DMAAP"* ]]; then
                use_agent_dmaap_https
            else
                use_agent_rest_https
            fi
        else
            use_cr_http
            use_simulator_http
            use_mr_http
            if [[ $interface = *"SDNC"* ]]; then
                use_sdnc_http
            fi
            if [[ $interface = *"DMAAP"* ]]; then
                use_agent_dmaap_http
            else
                use_agent_rest_http
            fi
        fi

        # Clean container and start all needed containers #
        clean_environment

        if [ $RUNMODE == "KUBE" ]; then
            start_kube_proxy
        fi

        start_ric_simulators ricsim_g1 4 OSC_2.1.0

        start_ric_simulators ricsim_g2 4 STD_1.1.3

        if [ "$PMS_VERSION" == "V2" ]; then
            start_ric_simulators ricsim_g3 4  STD_2.0.0
        fi

        start_mr

        start_cr

        if [ $RUNMODE == "DOCKER" ]; then
            start_consul_cbs
        fi

        start_control_panel $SIM_GROUP/$CONTROL_PANEL_COMPOSE_DIR/$CONTROL_PANEL_CONFIG_FILE

        if [ ! -z "$NRT_GATEWAY_APP_NAME" ]; then
            start_gateway $SIM_GROUP/$NRT_GATEWAY_COMPOSE_DIR/$NRT_GATEWAY_CONFIG_FILE
        fi

        start_policy_agent NORPOXY $SIM_GROUP/$POLICY_AGENT_COMPOSE_DIR/$POLICY_AGENT_CONFIG_FILE

        set_agent_debug

        if [[ $interface = *"SDNC"* ]]; then
            start_sdnc
            prepare_consul_config      SDNC    ".consul_config.json"
        else
            prepare_consul_config      NOSDNC  ".consul_config.json"
        fi

        if [ $RUNMODE == "KUBE" ]; then
            agent_load_config                       ".consul_config.json"
        else
            consul_config_app                      ".consul_config.json"
        fi

        api_get_status 200

        sim_print ricsim_g1_1 interface

        sim_print ricsim_g2_1 interface

        if [ "$PMS_VERSION" == "V2" ]; then
            sim_print ricsim_g3_1 interface
        fi

        sim_put_policy_type 201 ricsim_g1_1 1 testdata/OSC/sim_1.json

        if [ "$PMS_VERSION" == "V2" ]; then
            api_equal json:policy-types 2 120  #Wait for the agent to refresh types from the simulator
        else
            api_equal json:policy_types 2 120  #Wait for the agent to refresh types from the simulator
        fi

        api_put_service 201 "serv1" 3600 "$CR_SERVICE_PATH/1"

        START_ID=2000
        NUM_POLICIES=10000  # Must be at least 100

        if [ "$PMS_VERSION" == "V2" ]; then
            notificationurl=$CR_SERVICE_PATH"/test"
        else
            notificationurl=""
        fi

        if [[ $interface == *"BATCH"* ]]; then
            api_put_policy_batch 201 "serv1" ricsim_g1_1 1 $START_ID NOTRANSIENT $notificationurl testdata/OSC/pi1_template.json $NUM_POLICIES
        else
            api_put_policy 201 "serv1" ricsim_g1_1 1 $START_ID NOTRANSIENT $notificationurl testdata/OSC/pi1_template.json $NUM_POLICIES
        fi

        sim_equal ricsim_g1_1 num_instances $NUM_POLICIES

        sim_post_delete_instances 200 ricsim_g1_1

        sim_equal ricsim_g1_1 num_instances 0

        sim_equal ricsim_g1_1 num_instances $NUM_POLICIES 300

        START_ID2=$(($START_ID+$NUM_POLICIES))

        if [[ $interface == *"BATCH"* ]]; then
            api_put_policy_batch 201 "serv1" ricsim_g2_1 NOTYPE $START_ID2 NOTRANSIENT $notificationurl testdata/STD/pi1_template.json $NUM_POLICIES
        else
            api_put_policy 201 "serv1" ricsim_g2_1 NOTYPE $START_ID2 NOTRANSIENT $notificationurl testdata/STD/pi1_template.json $NUM_POLICIES
        fi
        sim_equal ricsim_g2_1 num_instances $NUM_POLICIES

        sim_post_delete_instances 200 ricsim_g2_1

        sim_equal ricsim_g2_1 num_instances 0

        sim_equal ricsim_g2_1 num_instances $NUM_POLICIES 300

        api_delete_policy 204 $(($START_ID+47))

        api_delete_policy 204 $(($START_ID+$NUM_POLICIES-39))

        sim_post_delete_instances 200 ricsim_g1_1

        sim_equal ricsim_g1_1 num_instances $(($NUM_POLICIES-2)) 300

        api_delete_policy 204 $(($START_ID2+37))

        api_delete_policy 204 $(($START_ID2+$NUM_POLICIES-93))

        api_delete_policy 204 $(($START_ID2+$NUM_POLICIES-91))

        sim_post_delete_instances 200 ricsim_g2_1

        sim_equal ricsim_g1_1 num_instances $(($NUM_POLICIES-2)) 300

        sim_equal ricsim_g2_1 num_instances $(($NUM_POLICIES-3)) 300

        api_equal json:policies $(($NUM_POLICIES-2+$NUM_POLICIES-3))


        if [[ $interface = *"SDNC"* ]]; then
            check_sdnc_logs
        fi

        check_policy_agent_logs
        check_sdnc_logs
        store_logs          "${__httpx}__${interface}"

    done

done


#### TEST COMPLETE ####


print_result

auto_clean_environment