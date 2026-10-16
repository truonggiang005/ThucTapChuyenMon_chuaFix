package com.smartschedule.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Entity đại diện cho Chi nhánh (Branch).
 * Mỗi chi nhánh có nhiều ca làm việc (shifts) và nhiều nhân viên thuộc về (employees).
 */
@Entity
@Table(name = "branches")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Branch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Tên chi nhánh không được để trống")
    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 300)
    private String address;

    @Positive(message = "Sức chứa tối đa phải là số dương")
    @Column(name = "max_capacity")
    private Integer maxCapacity;

    // ===== QUAN HỆ =====

    /**
     * Một chi nhánh có nhiều ca làm việc.
     * mappedBy = "branch" trỏ đến field 'branch' trong entity Shift.
     */
    @OneToMany(mappedBy = "branch", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Shift> shifts = new ArrayList<>();

    /**
     * Một chi nhánh có nhiều nhân viên chính (primary employees).
     * mappedBy = "primaryBranch" trỏ đến field 'primaryBranch' trong entity Employee.
     */
    @OneToMany(mappedBy = "primaryBranch", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Employee> employees = new ArrayList<>();
}
