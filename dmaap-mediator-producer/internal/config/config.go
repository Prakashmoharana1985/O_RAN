// -
//   ========================LICENSE_START=================================
//   O-RAN-SC
//   %%
//   Copyright (C) 2021: Nordix Foundation
//   %%
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//        http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.
//   ========================LICENSE_END===================================
//

package config

import (
	"os"
)

type Config struct {
	LogLevel                            string
	InfoProducerSupervisionCallbackHost string
	InfoProducerSupervisionCallbackPort string
	InfoJobCallbackHost                 string
	InfoJobCallbackPort                 string
	InfoCoordinatorAddress              string
}

type ProducerRegistrationInfo struct {
	InfoProducerSupervisionCallbackUrl string   `json:"info_producer_supervision_callback_url"`
	SupportedInfoTypes                 []string `json:"supported_info_types"`
	InfoJobCallbackUrl                 string   `json:"info_job_callback_url"`
}

func New() *Config {
	return &Config{
		LogLevel:                            getEnv("LOG_LEVEL", "Info"),
		InfoProducerSupervisionCallbackHost: getEnv("INFO_PRODUCER_SUPERVISION_CALLBACK_HOST", ""),
		InfoProducerSupervisionCallbackPort: getEnv("INFO_PRODUCER_SUPERVISION_CALLBACK_PORT", "8085"),
		InfoJobCallbackHost:                 getEnv("INFO_JOB_CALLBACK_HOST", ""),
		InfoJobCallbackPort:                 getEnv("INFO_JOB_CALLBACK_PORT", "8086"),
		InfoCoordinatorAddress:              getEnv("INFO_COORD_ADDR", "http://enrichmentservice:8083"),
	}
}

func getEnv(key string, defaultVal string) string {
	if value, exists := os.LookupEnv(key); exists {
		return value
	}

	return defaultVal
}
