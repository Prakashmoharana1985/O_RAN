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

package jobs

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/require"
)

const type1Schema = `{"title": "Type 1"}`

func TestGetTypes_filesOkShouldReturnSliceOfTypesAndProvideSupportedTypes(t *testing.T) {
	assertions := require.New(t)
	typesDir, err := os.MkdirTemp("", "configs")
	if err != nil {
		t.Errorf("Unable to create temporary directory for types due to: %v", err)
	}
	t.Cleanup(func() {
		os.RemoveAll(typesDir)
		clearAll()
	})
	typeDir = typesDir
	fname := filepath.Join(typesDir, "type1.json")
	if err = os.WriteFile(fname, []byte(type1Schema), 0666); err != nil {
		t.Errorf("Unable to create temporary files for types due to: %v", err)
	}
	types, err := GetTypes()
	wantedType := Type{
		TypeId: "type1",
		Schema: type1Schema,
	}
	wantedTypes := []*Type{&wantedType}
	assertions.EqualValues(wantedTypes, types)
	assertions.Nil(err)

	supportedTypes := GetSupportedTypes()
	assertions.EqualValues([]string{"type1"}, supportedTypes)
}

func TestAddJobWhenTypeIsSupported_shouldAddJobToAllJobsMap(t *testing.T) {
	assertions := require.New(t)
	allJobs["type1"] = make(map[string]JobInfo)
	t.Cleanup(func() {
		clearAll()
	})
	jobInfo := JobInfo{
		Owner:            "owner",
		LastUpdated:      "now",
		InfoJobIdentity:  "job1",
		TargetUri:        "target",
		InfoJobData:      "{}",
		InfoTypeIdentity: "type1",
	}

	err := AddJob(jobInfo)
	assertions.Nil(err)
	assertions.Equal(1, len(allJobs["type1"]))
	assertions.Equal(jobInfo, allJobs["type1"]["job1"])
}

func TestAddJobWhenTypeIsNotSupported_shouldReturnError(t *testing.T) {
	assertions := require.New(t)
	jobInfo := JobInfo{
		InfoTypeIdentity: "type1",
	}

	err := AddJob(jobInfo)
	assertions.NotNil(err)
	assertions.Equal("type not supported: type1", err.Error())
}

func TestAddJobWhenJobIdMissing_shouldReturnError(t *testing.T) {
	assertions := require.New(t)
	allJobs["type1"] = make(map[string]JobInfo)
	t.Cleanup(func() {
		clearAll()
	})
	jobInfo := JobInfo{
		InfoTypeIdentity: "type1",
	}

	err := AddJob(jobInfo)
	assertions.NotNil(err)
	assertions.Equal("missing required job identity: {     type1}", err.Error())
}

func TestAddJobWhenTargetUriMissing_shouldReturnError(t *testing.T) {
	assertions := require.New(t)
	allJobs["type1"] = make(map[string]JobInfo)
	jobInfo := JobInfo{
		InfoTypeIdentity: "type1",
		InfoJobIdentity:  "job1",
	}

	err := AddJob(jobInfo)
	assertions.NotNil(err)
	assertions.Equal("missing required target URI: {  job1   type1}", err.Error())
	clearAll()
}
