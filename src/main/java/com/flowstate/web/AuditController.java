package com.flowstate.web;

import com.flowstate.domain.User;
import com.flowstate.repository.AuditLogRepository;
import com.flowstate.security.CurrentUserService;
import com.flowstate.web.dto.AuditDtos.AuditLogResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit-log")
public class AuditController {

    private final AuditLogRepository auditLogRepository;
    private final CurrentUserService currentUserService;

    public AuditController(AuditLogRepository auditLogRepository, CurrentUserService currentUserService) {
        this.auditLogRepository = auditLogRepository;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public List<AuditLogResponse> list() {
        User user = currentUserService.getCurrentUser();
        return auditLogRepository.findByUserIdOrderByTimestampDesc(user.getId()).stream()
                .map(AuditLogResponse::from)
                .toList();
    }
}
