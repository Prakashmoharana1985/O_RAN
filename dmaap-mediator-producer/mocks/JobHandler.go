// Code generated by mockery v2.9.3. DO NOT EDIT.

package mocks

import (
	mock "github.com/stretchr/testify/mock"
	jobs "oransc.org/nonrtric/dmaapmediatorproducer/internal/jobs"
)

// JobHandler is an autogenerated mock type for the JobHandler type
type JobHandler struct {
	mock.Mock
}

// AddJob provides a mock function with given fields: _a0
func (_m *JobHandler) AddJob(_a0 jobs.JobInfo) error {
	ret := _m.Called(_a0)

	var r0 error
	if rf, ok := ret.Get(0).(func(jobs.JobInfo) error); ok {
		r0 = rf(_a0)
	} else {
		r0 = ret.Error(0)
	}

	return r0
}
