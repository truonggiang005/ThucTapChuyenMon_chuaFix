package com.smartschedule.controller;

import com.smartschedule.dto.BranchRequest;
import com.smartschedule.dto.SkillRequest;
import com.smartschedule.service.CatalogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Danh mục chi nhánh/kỹ năng.
 *   GET  /api/branches, /api/skills             → xem (mọi người dùng đã đăng nhập, cho form/bộ lọc)
 *   POST/PUT /api/branches[/{id}], /api/skills[/{id}] → tạo/sửa (ADMIN)
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;

    @GetMapping("/branches")
    public ResponseEntity<List<Map<String, Object>>> branches() {
        return ResponseEntity.ok(catalogService.listBranches());
    }

    @GetMapping("/skills")
    public ResponseEntity<List<Map<String, Object>>> skills() {
        return ResponseEntity.ok(catalogService.listSkills());
    }

    @PostMapping("/branches")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> createBranch(@Valid @RequestBody BranchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.createBranch(request));
    }

    @PutMapping("/branches/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> updateBranch(@PathVariable Long id,
                                                            @Valid @RequestBody BranchRequest request) {
        return ResponseEntity.ok(catalogService.updateBranch(id, request));
    }

    @PostMapping("/skills")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> createSkill(@Valid @RequestBody SkillRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.createSkill(request));
    }

    @PutMapping("/skills/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> updateSkill(@PathVariable Long id,
                                                           @Valid @RequestBody SkillRequest request) {
        return ResponseEntity.ok(catalogService.updateSkill(id, request));
    }
}
