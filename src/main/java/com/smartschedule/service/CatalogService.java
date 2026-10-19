package com.smartschedule.service;

import com.smartschedule.dto.BranchRequest;
import com.smartschedule.dto.SkillRequest;
import com.smartschedule.entity.Branch;
import com.smartschedule.entity.Skill;
import com.smartschedule.exception.BusinessException;
import com.smartschedule.exception.NotFoundException;
import com.smartschedule.repository.BranchRepository;
import com.smartschedule.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Danh mục chi nhánh và kỹ năng: xem (mọi người dùng) và tạo/sửa (chỉ ADMIN - A6). */
@Service
@RequiredArgsConstructor
@Slf4j
public class CatalogService {

    private final BranchRepository branchRepository;
    private final SkillRepository skillRepository;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listBranches() {
        return branchRepository.findAll(Sort.by("name")).stream().map(this::branchMap).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listSkills() {
        return skillRepository.findAll(Sort.by("name")).stream().map(this::skillMap).toList();
    }

    @Transactional
    public Map<String, Object> createBranch(BranchRequest r) {
        Branch saved = branchRepository.save(Branch.builder()
                .name(r.getName().trim())
                .address(r.getAddress() != null ? r.getAddress().trim() : null)
                .maxCapacity(r.getMaxCapacity())
                .build());
        log.info("[CATALOG] Đã tạo chi nhánh #{}", saved.getId());
        return branchMap(saved);
    }

    @Transactional
    public Map<String, Object> updateBranch(Long id, BranchRequest r) {
        Branch b = branchRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy chi nhánh #" + id));
        b.setName(r.getName().trim());
        b.setAddress(r.getAddress() != null ? r.getAddress().trim() : null);
        b.setMaxCapacity(r.getMaxCapacity());
        return branchMap(branchRepository.save(b));
    }

    @Transactional
    public Map<String, Object> createSkill(SkillRequest r) {
        String name = r.getName().trim();
        if (skillRepository.findByName(name).isPresent()) {
            throw new BusinessException("Kỹ năng \"" + name + "\" đã tồn tại");
        }
        Skill saved = skillRepository.save(Skill.builder().name(name).build());
        log.info("[CATALOG] Đã tạo kỹ năng #{}", saved.getId());
        return skillMap(saved);
    }

    @Transactional
    public Map<String, Object> updateSkill(Long id, SkillRequest r) {
        Skill s = skillRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy kỹ năng #" + id));
        String name = r.getName().trim();
        skillRepository.findByName(name).ifPresent(other -> {
            if (!other.getId().equals(id)) {
                throw new BusinessException("Kỹ năng \"" + name + "\" đã tồn tại");
            }
        });
        s.setName(name);
        return skillMap(skillRepository.save(s));
    }

    private Map<String, Object> branchMap(Branch b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("name", b.getName());
        m.put("address", b.getAddress());
        m.put("maxCapacity", b.getMaxCapacity());
        return m;
    }

    private Map<String, Object> skillMap(Skill s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        return m;
    }
}
