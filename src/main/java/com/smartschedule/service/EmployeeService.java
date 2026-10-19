package com.smartschedule.service;

import com.smartschedule.dto.*;
import com.smartschedule.entity.*;
import com.smartschedule.exception.BusinessException;
import com.smartschedule.exception.InvalidRequestException;
import com.smartschedule.exception.NotFoundException;
import com.smartschedule.repository.*;
import com.smartschedule.util.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Quản lý nhân viên (A6): tạo/sửa, đặt kỹ năng, đổi/đặt lại mật khẩu.
 * Trước đây hệ thống chỉ đọc được nhân viên, nên triển khai thật từ đầu không có tài khoản nào để đăng nhập.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeeService {

    private static final Set<String> PREFERRED_TIMES = Set.of("MORNING", "AFTERNOON", "EVENING", "NIGHT", "ANY");
    private static final int MAX_PAGE_SIZE = 500;

    private final EmployeeRepository employeeRepository;
    private final BranchRepository branchRepository;
    private final SkillRepository skillRepository;
    private final EmployeeSkillRepository employeeSkillRepository;
    private final PasswordEncoder passwordEncoder;

    /** Danh sách nhân viên (kèm chi nhánh, không N+1), có giới hạn số dòng. */
    @Transactional(readOnly = true)
    public List<EmployeeDTO> getEmployees(Integer page, Integer size) {
        int pageNo = page != null && page > 0 ? page : 0;
        int pageSize = size != null && size > 0 ? Math.min(size, MAX_PAGE_SIZE) : 200;
        List<Employee> employees = employeeRepository.findPageWithBranch(PageRequest.of(pageNo, pageSize));
        // Kỹ năng phải có trong danh sách: form sửa nhân viên lấy mức kỹ năng hiện tại từ đây,
        // nếu thiếu thì lưu form sẽ XÓA sạch kỹ năng của nhân viên (một truy vấn chung, không N+1).
        Map<Long, List<SkillLevelDTO>> skillsByEmployee = loadSkillsFor(
                employees.stream().map(Employee::getId).toList());
        return employees.stream()
                .map(e -> toDto(e, skillsByEmployee.getOrDefault(e.getId(), List.of())))
                .toList();
    }

    /** Chi tiết một nhân viên (kèm kỹ năng). */
    @Transactional(readOnly = true)
    public EmployeeDTO getEmployeeById(Long id) {
        Employee emp = employeeRepository.findByIdWithBranch(id)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy nhân viên #" + id));
        return toDto(emp, loadSkills(id));
    }

    @Transactional
    public EmployeeDTO create(CreateEmployeeRequest r) {
        String email = r.getEmail().trim().toLowerCase(Locale.ROOT);
        if (employeeRepository.existsByEmail(email)) {
            throw new BusinessException("Email này đã được sử dụng");
        }
        PasswordPolicy.validate(r.getPassword());

        Employee emp = Employee.builder()
                .fullName(r.getFullName().trim())
                .email(email)
                .passwordHash(passwordEncoder.encode(r.getPassword()))
                .role(parseRole(r.getRole(), Role.EMPLOYEE))
                .maxHoursPerWeek(r.getMaxHoursPerWeek() != null ? r.getMaxHoursPerWeek() : 40)
                .preferredTime(parsePreferredTime(r.getPreferredTime(), "ANY"))
                .primaryBranch(r.getPrimaryBranchId() != null ? findBranch(r.getPrimaryBranchId()) : null)
                .build();
        Employee saved = employeeRepository.save(emp);
        applySkills(saved, r.getSkills());
        log.info("[EMPLOYEE] Đã tạo nhân viên #{} ({})", saved.getId(), saved.getRole());
        return toDto(saved, loadSkills(saved.getId()));
    }

    @Transactional
    public EmployeeDTO update(Long id, UpdateEmployeeRequest r, Long actingAdminId) {
        Employee emp = employeeRepository.findByIdWithBranch(id)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy nhân viên #" + id));

        Role newRole = r.getRole() != null ? parseRole(r.getRole(), emp.getRole()) : emp.getRole();
        boolean newActive = r.getActive() != null ? r.getActive() : emp.isActive();

        boolean wasActiveAdmin = emp.getRole() == Role.ADMIN && emp.isActive();
        boolean willBeActiveAdmin = newRole == Role.ADMIN && newActive;
        if (wasActiveAdmin && !willBeActiveAdmin) {
            if (id.equals(actingAdminId)) {
                throw new BusinessException("Bạn không thể tự vô hiệu hóa hoặc hạ quyền chính mình");
            }
            if (employeeRepository.findActiveAdminIds().size() <= 1) {
                throw new BusinessException("Phải còn ít nhất một quản trị viên đang hoạt động");
            }
        }

        if (r.getFullName() != null && !r.getFullName().isBlank()) {
            emp.setFullName(r.getFullName().trim());
        }
        if (r.getMaxHoursPerWeek() != null) {
            emp.setMaxHoursPerWeek(r.getMaxHoursPerWeek());
        }
        if (r.getPreferredTime() != null) {
            emp.setPreferredTime(parsePreferredTime(r.getPreferredTime(), "ANY"));
        }
        if (r.getPrimaryBranchId() != null) {
            emp.setPrimaryBranch(findBranch(r.getPrimaryBranchId()));
        }
        emp.setRole(newRole);
        emp.setActive(newActive);

        Employee saved = employeeRepository.save(emp);
        if (r.getSkills() != null) {
            applySkills(saved, r.getSkills());
        }
        log.info("[EMPLOYEE] Đã cập nhật nhân viên #{}", id);
        return toDto(saved, loadSkills(id));
    }

    /** Đổi mật khẩu của CHÍNH mình: phải nhập đúng mật khẩu hiện tại. */
    @Transactional
    public void changePassword(Long employeeId, ChangePasswordRequest r) {
        Employee emp = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy nhân viên #" + employeeId));
        if (!passwordEncoder.matches(r.getCurrentPassword(), emp.getPasswordHash())) {
            throw new InvalidRequestException("Mật khẩu hiện tại không đúng");
        }
        if (r.getCurrentPassword().equals(r.getNewPassword())) {
            throw new InvalidRequestException("Mật khẩu mới phải khác mật khẩu hiện tại");
        }
        PasswordPolicy.validate(r.getNewPassword());
        emp.setPasswordHash(passwordEncoder.encode(r.getNewPassword()));
        employeeRepository.save(emp);
        log.info("[EMPLOYEE] Nhân viên #{} đã đổi mật khẩu", employeeId);
    }

    /** Admin đặt lại mật khẩu cho một nhân viên (đồng thời mở khóa tài khoản). */
    @Transactional
    public void resetPassword(Long targetId, ResetPasswordRequest r) {
        Employee emp = employeeRepository.findById(targetId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy nhân viên #" + targetId));
        PasswordPolicy.validate(r.getNewPassword());
        emp.setPasswordHash(passwordEncoder.encode(r.getNewPassword()));
        emp.setFailedLoginCount(0);
        emp.setLockedUntil(null);
        employeeRepository.save(emp);
        log.info("[EMPLOYEE] Admin đã đặt lại mật khẩu cho nhân viên #{}", targetId);
    }

    // ---------------- nội bộ ----------------

    /** Đặt danh sách kỹ năng: cập nhật mức, thêm mới, xóa kỹ năng không còn trong danh sách. */
    private void applySkills(Employee emp, List<SkillLevelDTO> wanted) {
        if (wanted == null) {
            return;
        }
        Map<Long, Integer> target = new LinkedHashMap<>();
        for (SkillLevelDTO s : wanted) {
            if (target.put(s.getSkillId(), s.getLevel()) != null) {
                throw new InvalidRequestException("Kỹ năng #" + s.getSkillId() + " bị trùng trong danh sách");
            }
        }
        Map<Long, Skill> skills = skillRepository.findAllById(target.keySet()).stream()
                .collect(Collectors.toMap(Skill::getId, s -> s));
        for (Long skillId : target.keySet()) {
            if (!skills.containsKey(skillId)) {
                throw new NotFoundException("Không tìm thấy kỹ năng #" + skillId);
            }
        }

        List<EmployeeSkill> current = employeeSkillRepository.findByEmployeeId(emp.getId());
        Set<Long> kept = new HashSet<>();
        for (EmployeeSkill es : current) {
            Long skillId = es.getSkill().getId();
            Integer level = target.get(skillId);
            if (level == null) {
                employeeSkillRepository.delete(es);
            } else {
                es.setSkillLevel(level);
                kept.add(skillId);
            }
        }
        for (Map.Entry<Long, Integer> e : target.entrySet()) {
            if (!kept.contains(e.getKey())) {
                employeeSkillRepository.save(EmployeeSkill.builder()
                        .employee(emp).skill(skills.get(e.getKey())).skillLevel(e.getValue()).build());
            }
        }
    }

    private Map<Long, List<SkillLevelDTO>> loadSkillsFor(Collection<Long> employeeIds) {
        Map<Long, List<SkillLevelDTO>> byEmployee = new HashMap<>();
        if (employeeIds.isEmpty()) {
            return byEmployee;
        }
        for (EmployeeSkill es : employeeSkillRepository.findAllForEmployees(employeeIds)) {
            byEmployee.computeIfAbsent(es.getEmployee().getId(), k -> new ArrayList<>()).add(toSkillDto(es));
        }
        byEmployee.values().forEach(list -> list.sort(Comparator.comparing(SkillLevelDTO::getSkillName)));
        return byEmployee;
    }

    private List<SkillLevelDTO> loadSkills(Long employeeId) {
        return employeeSkillRepository.findByEmployeeId(employeeId).stream()
                .map(this::toSkillDto)
                .sorted(Comparator.comparing(SkillLevelDTO::getSkillName))
                .toList();
    }

    private SkillLevelDTO toSkillDto(EmployeeSkill es) {
        return SkillLevelDTO.builder()
                .skillId(es.getSkill().getId())
                .skillName(es.getSkill().getName())
                .level(es.getSkillLevel())
                .build();
    }

    private Branch findBranch(Long id) {
        return branchRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy chi nhánh #" + id));
    }

    private static Role parseRole(String value, Role fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Role.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Vai trò không hợp lệ (ADMIN hoặc EMPLOYEE)");
        }
    }

    private static String parsePreferredTime(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        if (!PREFERRED_TIMES.contains(v)) {
            throw new InvalidRequestException("Khung giờ ưa thích không hợp lệ (MORNING, AFTERNOON, EVENING, NIGHT, ANY)");
        }
        return v;
    }

    private EmployeeDTO toDto(Employee emp, List<SkillLevelDTO> skills) {
        return EmployeeDTO.builder()
                .id(emp.getId())
                .fullName(emp.getFullName())
                .email(emp.getEmail())
                .role(emp.getRole() != null ? emp.getRole().name() : Role.EMPLOYEE.name())
                .active(emp.isActive())
                .maxHoursPerWeek(emp.getMaxHoursPerWeek())
                .totalCompleted(emp.getTotalCompleted())
                .totalCancelled(emp.getTotalCancelled())
                .lateArrivalCount(emp.getLateArrivalCount())
                .preferredTime(emp.getPreferredTime())
                .primaryBranchId(emp.getPrimaryBranch() != null ? emp.getPrimaryBranch().getId() : null)
                .primaryBranchName(emp.getPrimaryBranch() != null ? emp.getPrimaryBranch().getName() : null)
                .skills(skills)
                .build();
    }
}
