package com.smartschedule.repository;

import com.smartschedule.entity.Branch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository cho Entity Branch.
 * JpaRepository cung cấp sẵn: findAll, findById, save, deleteById, count,...
 */
@Repository
public interface BranchRepository extends JpaRepository<Branch, Long> {

}
