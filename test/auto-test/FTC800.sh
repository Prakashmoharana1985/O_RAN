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

TC_ONELINE_DESCR="Create 10000 policies in sequence using http/https and Agent REST/DMAAP with/without SDNC controller"

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
. ../common/ricsimulator_api_functions.sh
. ../common/cr_api_functions.sh
. ../common/mr_api_functions.sh
. ../common/control_panel_api_functions.sh
. ../common/controller_api_functions.sh
. ../common/consul_cbs_functions.sh
. ../common/kube_proxy_api_functions.sh
. ../common/gateway_api_functions.sh

setup_testenvironment

#### TEST BEGIN ####

generate_policy_uuid

#Local vars in test script
##########################
# Number of policies in each sequence
NUM_POLICIES=10000

# Tested variants of REST/DMAAP/SDNC config
TESTED_VARIANTS="NOSDNC   SDNC"

#Test agent and simulator protocol versions (others are http only)
TESTED_PROTOCOLS="HTTP HTTPS"

for __httpx in $TESTED_PROTOCOLS ; do
    for interface in $TESTED_VARIANTS ; do

        echo "#####################################################################"
        echo "#####################################################################"
        echo "### Testing agent via $interface using $__httpx"
        echo "#####################################################################"
        echo "#####################################################################"

        if [ $__httpx == "HTTPS" ]; then
            use_cr_https
            use_simulator_https
            use_mr_https
            use_agent_rest_https
        else
            use_cr_http
            use_simulator_http
            use_mr_http
            use_agent_rest_http
        fi

        # Policy instance start id
        START_ID=1

        clean_environment

        if [ $RUNMODE == "KUBE" ]; then
            start_kube_proxy
        fi

        start_ric_simulators ricsim_g1 1 OSC_2.1.0
        start_ric_simulators ricsim_g2 1 STD_1.1.3
        if [ "$PMS_VERSION" == "V2" ]; then
            start_ric_simulators ricsim_g3 1  STD_2.0.0
        fi

        start_mr

        start_cr

        start_control_panel $SIM_GROUP/$CONTROL_PANEL_COMPOSE_DIR/$CONTROL_PANEL_CONFIG_FILE

        if [ ! -z "$NRT_GATEWAY_APP_NAME" ]; then
            start_gateway $SIM_GROUP/$NRT_GATEWAY_COMPOSE_DIR/$NRT_GATEWAY_CONFIG_FILE
        fi

        start_policy_agent NORPOXY $SIM_GROUP/$POLICY_AGENT_COMPOSE_DIR/$POLICY_AGENT_CONFIG_FILE

        set_agent_debug

        mr_equal requests_submitted 0

        if [[ $interface == "SDNC" ]]; then
            start_sdnc
            prepare_consul_config      SDNC    ".consul_config.json"
        else
            prepare_consul_config      NOSDNC  ".consul_config.json"
        fi

        if [ $RUNMODE == "DOCKER" ]; then
            start_consul_cbs
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
            sim_put_policy_type 201 ricsim_g3_1 STD_QOS2_0.1.0 testdata/STD2/sim_qos2.json

            api_equal json:policy-types 3 300  #Wait for the agent to refresh types from the simulators
        else
            api_equal json:policy_types 2 300  #Wait for the agent to refresh types from the simulators
        fi

        api_put_service 201 "serv1" 3600 "$CR_SERVICE_PATH/1"

        if [ "$PMS_VERSION" == "V2" ]; then
            notificationurl=$CR_SERVICE_PATH"/test"
        else
            notificationurl=""
        fi

        start_timer "Create polices in OSC via agent REST and $interface using "$__httpx
        api_put_policy 201 "serv1" ricsim_g1_1 1 $START_ID NOTRANSIENT $notificationurl testdata/OSC/pi1_template.json $NUM_POLICIES
        print_timer "Create polices in OSC via agent REST and $interface using "$__httpx

        sim_equal ricsim_g1_1 num_instances $NUM_POLICIES

        START_ID=$(($START_ID+$NUM_POLICIES))

        start_timer "Create polices in STD via agent REST and $interface using "$__httpx
        api_put_policy 201 "serv1" ricsim_g2_1 NOTYPE $START_ID NOTRANSIENT $notificationurl testdata/STD/pi1_template.json $NUM_POLICIES
        print_timer "Create polices in STD via agent REST and $interface using "$__httpx

        sim_equal ricsim_g2_1 num_instances $NUM_POLICIES

        if [ "$PMS_VERSION" == "V2" ]; then

            START_ID=$(($START_ID+$NUM_POLICIES))

            start_timer "Create polices in STD 2 via agent REST and $interface using "$__httpx
            api_put_policy 201 "serv1" ricsim_g3_1 STD_QOS2_0.1.0 $START_ID NOTRANSIENT $notificationurl testdata/STD2/pi_qos2_template.json $NUM_POLICIES
            print_timer "Create polices in STD via agent REST and $interface using "$__httpx

            sim_equal ricsim_g3_1 num_instances $NUM_POLICIES
        fi

        if [ $__httpx == "HTTPS" ]; then
            echo "Using secure ports towards dmaap"
            use_agent_dmaap_https
        else
            echo "Using non-secure ports towards dmaap"
            use_agent_dmaap_http
        fi

        START_ID=$(($START_ID+$NUM_POLICIES))

        start_timer "Create polices in OSC via agent DMAAP, one by one, and $interface using "$__httpx
        api_put_policy 201 "serv1" ricsim_g1_1 1 $START_ID NOTRANSIENT $notificationurl testdata/OSC/pi1_template.json $NUM_POLICIES
        print_timer "Create polices in OSC via agent DMAAP, one by one, and $interface using "$__httpx

        sim_equal ricsim_g1_1 num_instances $((2*$NUM_POLICIES))

        START_ID=$(($START_ID+$NUM_POLICIES))

        start_timer "Create polices in STD via agent DMAAP, one by one, and $interface using "$__httpx
        api_put_policy 201 "serv1" ricsim_g2_1 NOTYPE $START_ID NOTRANSIENT $notificationurl testdata/STD/pi1_template.json $NUM_POLICIES
        print_timer "Create polices in STD via agent DMAAP, one by one, and $interface using "$__httpx

        sim_equal ricsim_g2_1 num_instances $((2*$NUM_POLICIES))

        if [ "$PMS_VERSION" == "V2" ]; then

            START_ID=$(($START_ID+$NUM_POLICIES))

            start_timer "Create polices in STD 2 via agent DMAAP, one by one, and $interface using "$__httpx
            api_put_policy 201 "serv1" ricsim_g3_1 STD_QOS2_0.1.0 $START_ID NOTRANSIENT $notificationurl testdata/STD2/pi_qos2_template.json $NUM_POLICIES
            print_timer "Create polices in STD via agent DMAAP, one by one, and $interface using "$__httpx

            sim_equal ricsim_g3_1 num_instances $((2*$NUM_POLICIES))
        fi

        START_ID=$(($START_ID+$NUM_POLICIES))

        start_timer "Create polices in OSC via agent DMAAP in batch and $interface using "$__httpx
        api_put_policy_batch 201 "serv1" ricsim_g1_1 1 $START_ID NOTRANSIENT $notificationurl testdata/OSC/pi1_template.json $NUM_POLICIES
        print_timer "Create polices in OSC via agent DMAAP in batch and $interface using "$__httpx

        sim_equal ricsim_g1_1 num_instances $((3*$NUM_POLICIES))

        START_ID=$(($START_ID+$NUM_POLICIES))

        start_timer "Create polices in STD via agent DMAAP in batch and $interface using "$__httpx
        api_put_policy_batch 201 "serv1" ricsim_g2_1 NOTYPE $START_ID NOTRANSIENT $notificationurl testdata/STD/pi1_template.json $NUM_POLICIES
        print_timer "Create polices in STD via agent DMAAP in batch and $interface using "$__httpx

        sim_equal ricsim_g2_1 num_instances $((3*$NUM_POLICIES))

        if [ "$PMS_VERSION" == "V2" ]; then

            START_ID=$(($START_ID+$NUM_POLICIES))

            start_timer "Create polices in STD via agent DMAAP in batch and $interface using "$__httpx
            api_put_policy_batch 201 "serv1" ricsim_g3_1 STD_QOS2_0.1.0 $START_ID NOTRANSIENT $notificationurl testdata/STD2/pi_qos2_template.json $NUM_POLICIES
            print_timer "Create polices in STD via agent DMAAP in batch and $interface using "$__httpx

            sim_equal ricsim_g3_1 num_instances $((3*$NUM_POLICIES))
        fi

        if [ $interface == "SDNC" ]; then
            sim_contains_str ricsim_g1_1 remote_hosts $SDNC_APP_NAME
            sim_contains_str ricsim_g2_1 remote_hosts $SDNC_APP_NAME
            if [ "$PMS_VERSION" == "V2" ]; then
                sim_contains_str ricsim_g3_1 remote_hosts $SDNC_APP_NAME
            fi
        else
            sim_contains_str ricsim_g1_1 remote_hosts $POLICY_AGENT_APP_NAME
            sim_contains_str ricsim_g2_1 remote_hosts $POLICY_AGENT_APP_NAME
            if [ "$PMS_VERSION" == "V2" ]; then
                sim_contains_str ricsim_g3_1 remote_hosts $POLICY_AGENT_APP_NAME
            fi
        fi

        check_policy_agent_logs
        if [[ $interface = *"SDNC"* ]]; then
            check_sdnc_logs
        fi

        store_logs          "${__httpx}__${interface}"
    done
done

#### TEST COMPLETE ####

print_result

auto_clean_environment