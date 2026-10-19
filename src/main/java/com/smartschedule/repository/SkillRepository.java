package com.smartschedule.repository;

import com.smartschedule.entity.Skill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository cho Entity Skill.
 */
@Repository
public interface SkillRepository extends JpaRepository<Skill, Long> {

    /** Tìm kỹ năng theo tên (dùng khi seed data hoặc tìm kiếm) */
    Optional<Skill> findByName(String name);
}
