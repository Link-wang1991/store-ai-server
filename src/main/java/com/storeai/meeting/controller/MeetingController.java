package com.storeai.meeting.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.storeai.common.dto.ApiResponse;
import com.storeai.common.util.CurrentUser;
import com.storeai.common.exception.BizException;
import com.storeai.meeting.entity.Meeting;
import com.storeai.meeting.repository.MeetingRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

@Tag(name = "会谈管理")
@RestController
@RequestMapping("/api/meetings")
@RequiredArgsConstructor
public class MeetingController {

    private final MeetingRepository meetingRepo;
    private final CurrentUser cur;

    @GetMapping
    public ApiResponse<List<Meeting>> list() {
        var qw = new LambdaQueryWrapper<Meeting>()
                .eq(Meeting::getStoreId, cur.storeId());
        if (!cur.isAdmin()) {
            qw.eq(Meeting::getEmployeeId, cur.employeeId());
        }
        qw.orderByDesc(Meeting::getCreatedAt);
        return ApiResponse.ok(meetingRepo.selectList(qw));
    }

    @PostMapping
    public ApiResponse<Meeting> create(@RequestBody CreateMeetingRequest req) {
        Meeting m = new Meeting();
        m.setStoreId(cur.storeId());
        m.setEmployeeId(cur.employeeId());
        m.setCustomerId(req.customerId());
        m.setScene(req.scene());
        m.setStatus("recording");
        m.setCreatedAt(OffsetDateTime.now());
        m.setUpdatedAt(OffsetDateTime.now());
        meetingRepo.insert(m);
        return ApiResponse.ok(m);
    }

    @PostMapping("/{id}/delete")
    public ApiResponse<Void> delete(@PathVariable String id) {
        Meeting m = meetingRepo.selectById(id);
        if (m == null || !cur.storeId().equals(m.getStoreId())) {
            throw BizException.notFound("会谈记录");
        }
        meetingRepo.deleteById(id);
        return ApiResponse.ok();
    }

    public record CreateMeetingRequest(String customerId, String scene) {}
}
