package com.infeara.sales.automation.job;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobExecutionRepository extends JpaRepository<JobExecution, String> {

    List<JobExecution> findByJobIdOrderByStartedAtDesc(String jobId);
}
