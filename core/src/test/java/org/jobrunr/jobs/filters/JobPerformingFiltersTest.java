package org.jobrunr.jobs.filters;

import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.JobVersioner;
import org.jobrunr.jobs.states.JobState;
import org.jobrunr.stubs.TestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.internal.util.reflection.Whitebox;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.jobrunr.jobs.JobDetailsTestBuilder.classThatDoesNotExistJobDetails;
import static org.jobrunr.jobs.JobDetailsTestBuilder.methodThatDoesNotExistJobDetails;
import static org.jobrunr.jobs.JobTestBuilder.*;
import static org.jobrunr.jobs.states.StateName.*;

class JobPerformingFiltersTest {

    private TestService testService;

    @BeforeEach
    void setUp() {
        testService = new TestService();
    }

    @Test
    void ifNoElectStateFilterIsProvidedTheDefaultRetryFilterIsUsed() {
        Job aJobWithoutJobFilters = aFailedJob().build();
        jobPerformingFilters(aJobWithoutJobFilters).runOnStateElectionFilter();
        assertThat(aJobWithoutJobFilters.getJobStates())
                .extracting("state")
                .containsExactly(ENQUEUED, PROCESSING, FAILED, SCHEDULED);
    }

    @Test
    void ifElectStateFilterIsProvidedItIsUsed() {
        Job aJobWithACustomElectStateJobFilter = anEnqueuedJob().withJobDetails(() -> testService.doWorkWithCustomJobFilters()).build();
        jobPerformingFilters(aJobWithACustomElectStateJobFilter).runOnStateElectionFilter();
        assertThat(aJobWithACustomElectStateJobFilter.getJobStates())
                .extracting("state")
                .containsExactly(ENQUEUED, SUCCEEDED);
    }

    @Test
    void ifADefaultElectStateFilterIsProvidedItIsUsed() {
        JobDefaultFilters jobDefaultFilters = new JobDefaultFilters(new TestService.FailedToDeleteElectStateFilter());
        Job aJobWithoutJobFilters = aFailedJob().build();
        jobPerformingFilters(aJobWithoutJobFilters, jobDefaultFilters).runOnStateElectionFilter();
        assertThat(aJobWithoutJobFilters.getJobStates())
                .extracting("state")
                .containsExactly(ENQUEUED, PROCESSING, FAILED, DELETED);
    }

    @Test
    void ifOtherFilterIsProvidedItIsUsed() {
        Job aJobWithACustomElectStateJobFilter = anEnqueuedJob().withJobDetails(() -> testService.doWorkWithCustomJobFilters()).build();
        JobPerformingFilters jobPerformingFilters = jobPerformingFilters(aJobWithACustomElectStateJobFilter);
        jobPerformingFilters.runOnStateAppliedFilters();
        jobPerformingFilters.runOnJobProcessingFilters();
        jobPerformingFilters.runOnJobProcessingSucceededFilters();
        Map<String, Object> metadata = Whitebox.getInternalState(aJobWithACustomElectStateJobFilter, "metadata");

        assertThat(metadata)
                .containsKey("onStateApplied")
                .containsKey("onProcessing")
                .containsKey("onProcessed");
    }

    @Test
    void ifOtherFilterUsesDependencyInjectionThisWillThrowRuntimeException() {
        JobDefaultFilters jobDefaultFilters = new JobDefaultFilters();

        Job aJobWithoutJobFilters = aJobInProgress().withJobDetails(() -> testService.doWorkWithCustomJobFilterThatNeedsDependencyInjection()).build();

        assertThatThrownBy(() -> jobPerformingFilters(aJobWithoutJobFilters, jobDefaultFilters).runOnJobProcessingFilters())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Do you want to use JobFilter Beans? This is only possible in the Pro version. Check out https://www.jobrunr.io/en/documentation/pro/job-filters/");
    }

    @Test
    void ifNoStateChangeHappensFilterIsNotInvoked() {
        JobTestFilter jobTestFilter = new JobTestFilter();
        JobDefaultFilters jobDefaultFilters = new JobDefaultFilters(jobTestFilter);

        Job aJobInProgress = aJobInProgress().build();
        try (JobVersioner jobVersioner = new JobVersioner(aJobInProgress)) {
            // saved to DB within this construct
            jobVersioner.commitVersion();
        }


        jobPerformingFilters(aJobInProgress, jobDefaultFilters).runOnStateElectionFilter();
        jobPerformingFilters(aJobInProgress, jobDefaultFilters).runOnStateAppliedFilters();

        assertThat(jobTestFilter)
                .hasFieldOrPropertyWithValue("onStateElectionInvoked", false)
                .hasFieldOrPropertyWithValue("onStateAppliedInvoked", false);
    }

    @Test
    void exceptionsAreCatched() {
        JobDefaultFilters jobDefaultFilters = new JobDefaultFilters(new JobFilterThatThrowsAnException());

        Job aJobWithoutJobFilters = aFailedJob().build();
        jobPerformingFilters(aJobWithoutJobFilters, jobDefaultFilters).runOnStateAppliedFilters();
        assertThat(aJobWithoutJobFilters.getJobStates())
                .extracting("state")
                .containsExactly(ENQUEUED, PROCESSING, FAILED);
    }

    @Test
    void noExceptionIsThrownIfJobClassIsNotFound() {
        Job aJobClassThatDoesNotExist = anEnqueuedJob().withJobDetails(classThatDoesNotExistJobDetails()).build();
        assertThatCode(() -> jobPerformingFilters(aJobClassThatDoesNotExist).runOnStateElectionFilter()).doesNotThrowAnyException();
        assertThatCode(() -> jobPerformingFilters(aJobClassThatDoesNotExist).runOnStateAppliedFilters()).doesNotThrowAnyException();
    }

    @Test
    void noExceptionIsThrownIfJobMethodIsNotFound() {
        Job aJobMethodThatDoesNotExist = anEnqueuedJob().withJobDetails(methodThatDoesNotExistJobDetails()).build();
        assertThatCode(() -> jobPerformingFilters(aJobMethodThatDoesNotExist).runOnStateElectionFilter()).doesNotThrowAnyException();
        assertThatCode(() -> jobPerformingFilters(aJobMethodThatDoesNotExist).runOnStateAppliedFilters()).doesNotThrowAnyException();
    }

    private JobPerformingFilters jobPerformingFilters(Job job) {
        return jobPerformingFilters(job, new JobDefaultFilters());
    }

    private JobPerformingFilters jobPerformingFilters(Job job, JobDefaultFilters jobDefaultFilters) {
        return new JobPerformingFilters(job, jobDefaultFilters);
    }

    private static class JobFilterThatThrowsAnException implements ApplyStateFilter {

        @Override
        public void onStateApplied(Job job, JobState oldState, JobState newState) {
            throw new RuntimeException("boem!");
        }
    }

    private static class JobTestFilter implements ElectStateFilter, ApplyStateFilter {

        private boolean onStateElectionInvoked;
        private boolean onStateAppliedInvoked;

        public JobTestFilter() {
            this.onStateElectionInvoked = false;
            this.onStateAppliedInvoked = false;
        }

        @Override
        public void onStateElection(Job job, JobState newState) {
            this.onStateElectionInvoked = true;
        }

        @Override
        public void onStateApplied(Job job, JobState oldState, JobState newState) {
            this.onStateAppliedInvoked = true;
        }
    }

}