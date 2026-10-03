package com.nexusapi.server.modules.notification.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.admin.service.AdminAuditService;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class AnnouncementService {
    public record Input(@NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 10000) String content) {}
    public record Edit(@NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 10000) String content,
                       @NotNull @Min(0) Long version) {}
    public record Version(@NotNull @Min(0) Long version) {}
    public record Announcement(UUID id, String title, String content, String status, long version,
            @JsonProperty("created_at") Instant createdAt, @JsonProperty("published_at") Instant publishedAt,
            @JsonProperty("read_at") Instant readAt) {}
    public record Feed(PageResponse<Announcement> announcements, @JsonProperty("unread_count") long unreadCount,
                       @JsonProperty("next_unread") Announcement nextUnread) {}
    private final JdbcTemplate jdbc;
    private final AdminAuditService audit;
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static final RowMapper<Announcement> ROW = (rs, n) -> new Announcement(
            rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("content"), rs.getString("status"),
            rs.getLong("version"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("published_at")),
            instant(rs.getTimestamp("read_at")));

    public AnnouncementService(JdbcTemplate jdbc, AdminAuditService audit) { this.jdbc = jdbc; this.audit = audit; }
    public PageResponse<Announcement> list(int page, int size) {
        return new PageResponse<>(jdbc.query("SELECT a.*, NULL::timestamptz AS read_at FROM announcements a ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                ROW, size, (page - 1) * size), jdbc.queryForObject("SELECT count(*) FROM announcements", Long.class), page, size);
    }
    private Announcement get(UUID id) {
        return jdbc.query("SELECT a.*, NULL::timestamptz AS read_at FROM announcements a WHERE id = ?", ROW, id)
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
    @Transactional
    public Announcement create(Input input, UUID actor, ClientRequestMetadata metadata) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO announcements (id, title, content, created_by) VALUES (?, ?, ?, ?)",
                id, input.title().strip(), input.content().strip(), actor);
        audit.record(actor, "announcement.create", "announcement", id, null, Map.of("status", "draft"), metadata);
        return get(id);
    }
    @Transactional
    public Announcement edit(UUID id, Edit input, UUID actor, ClientRequestMetadata metadata) {
        requireChanged(jdbc.update("""
                UPDATE announcements SET title = ?, content = ?, version = version + 1, updated_at = now()
                WHERE id = ? AND status = 'draft' AND version = ?
                """, input.title().strip(), input.content().strip(), id, input.version()));
        audit.record(actor, "announcement.edit", "announcement", id, null, Map.of("version", input.version() + 1), metadata);
        return get(id);
    }
    @Transactional
    public Announcement transition(UUID id, Version input, boolean publish, UUID actor, ClientRequestMetadata metadata) {
        String from = publish ? "draft" : "published";
        String to = publish ? "published" : "withdrawn";
        requireChanged(jdbc.update("""
                UPDATE announcements SET status = ?, version = version + 1, updated_at = now(),
                  published_at = CASE WHEN ? THEN now() ELSE published_at END
                WHERE id = ? AND status = ? AND version = ?
                """, to, publish, id, from, input.version()));
        audit.record(actor, "announcement." + (publish ? "publish" : "withdraw"), "announcement", id,
                Map.of("status", from), Map.of("status", to), metadata);
        return get(id);
    }
    private void requireChanged(int count) {
        if (count != 1) throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
    }
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Feed feed(UUID user, int page, int size) {
        var items = jdbc.query("""
                SELECT a.*, r.read_at FROM announcements a
                LEFT JOIN announcement_reads r ON r.announcement_id = a.id AND r.user_id = ?
                WHERE a.status = 'published' ORDER BY a.published_at DESC, a.id LIMIT ? OFFSET ?
                """, ROW, user, size, (page - 1) * size);
        long total = jdbc.queryForObject("SELECT count(*) FROM announcements WHERE status = 'published'", Long.class);
        long unread = jdbc.queryForObject("""
                SELECT count(*) FROM announcements a WHERE status = 'published'
                AND NOT EXISTS (SELECT 1 FROM announcement_reads r WHERE r.announcement_id = a.id AND r.user_id = ?)
                """, Long.class, user);
        var pending = jdbc.query("""
                SELECT a.*, NULL::timestamptz AS read_at FROM announcements a
                WHERE a.status = 'published' AND NOT EXISTS (
                    SELECT 1 FROM announcement_reads r WHERE r.announcement_id = a.id AND r.user_id = ?)
                ORDER BY a.published_at DESC, a.id LIMIT 1
                """, ROW, user);
        return new Feed(new PageResponse<>(items, total, page, size), unread, pending.isEmpty() ? null : pending.getFirst());
    }
    @Transactional
    public void markRead(UUID user, UUID id) {
        // Lock the publication while inserting a receipt, so a withdrawn/draft ID cannot be marked read.
        var published = jdbc.queryForList("SELECT id FROM announcements WHERE id = ? AND status = 'published' FOR SHARE", UUID.class, id);
        if (published.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        jdbc.update("INSERT INTO announcement_reads (announcement_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING", id, user);
    }
}
