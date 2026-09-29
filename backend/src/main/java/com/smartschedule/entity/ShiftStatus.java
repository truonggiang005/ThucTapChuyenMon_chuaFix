package com.smartschedule.entity;

/**
 * Enum đại diện cho trạng thái vòng đời của một ca làm việc (Shift).
 *
 * Luồng trạng thái:
 *   ASSIGNED → OPEN (nhân viên nhả ca)
 *   OPEN → TAKEN (nhân viên khác nhận ca thủ công)
 *   OPEN → FORCE_ASSIGNED (Cron Job tự động gán bằng AI Match Score)
 *   OPEN → UNFILLED (hết thời gian, không ai nhận)
 */
public enum ShiftStatus {

    /** Ca đã được phân công cho nhân viên ban đầu */
    ASSIGNED,

    /** Ca đang mở - nhân viên gốc đã nhả, chờ người khác nhận */
    OPEN,

    /** Ca đã được nhân viên khác nhận thủ công (qua giao diện) */
    TAKEN,

    /** Ca đã được hệ thống tự động gán (Cron Job + AI) */
    FORCE_ASSIGNED,

    /** Ca không có ai nhận, đã quá hạn */
    UNFILLED
}
