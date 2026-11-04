package com.dsports.support.interfaces;

import com.dsports.support.application.port.SupportCaseRepository;
import com.dsports.support.application.usecase.StaffCaseUseCase;
import com.dsports.support.domain.exception.SupportDomainException;
import com.dsports.support.domain.exception.SupportErrorCode;
import com.dsports.support.domain.model.CasePriority;
import com.dsports.support.domain.model.CaseStatus;
import com.dsports.support.domain.model.ResolutionOutcome;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Support operations. The role is enforced here (and again by the security configuration): a customer token gets 403 for every
 * route, whatever the front end shows.
 */
@RestController
@RequestMapping(path = "/api/v1/support/staff", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAnyRole('SUPPORT_EXECUTIVE','ADMIN')")
@Tag(name = "Support Staff")
public class StaffSupportController {

    private final StaffCaseUseCase staff;
    private final ZoneId businessZone;

    /** `businessZone`: the time zone in which a calendar day is counted for the date filters and the period report (UTC until the business agrees one). */
    public StaffSupportController(StaffCaseUseCase staff, @Value("${app.support.business-zone:UTC}") String businessZone) {
        this.staff = staff;
        this.businessZone = ZoneId.of(businessZone);
    }

    @GetMapping("/cases")
    @Operation(summary = "Search support cases", description = "Most urgent first. Filters are combined with AND.")
    public Mono<SupportResponses.StaffPage> search(@RequestParam(required = false) String status, @RequestParam(required = false) String category,
                                                   @RequestParam(required = false) String priority, @RequestParam(required = false) String escalation,
                                                   @RequestParam(required = false) Boolean unassigned, @RequestParam(required = false) UUID assignedTo,
                                                   @RequestParam(required = false) String queue, @RequestParam(required = false, name = "q") String text,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
                                                   @RequestParam(required = false) UUID customerId,
                                                   @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        var from = createdFrom == null ? null : createdFrom.atStartOfDay(businessZone).toInstant();
        var to = createdTo == null ? null : createdTo.plusDays(1).atStartOfDay(businessZone).toInstant();   // createdTo is inclusive; days are counted in the business zone
        var filter = new SupportCaseRepository.CaseFilter(upper(status), upper(category), upper(priority), upper(escalation), unassigned, assignedTo,
            SupportCaseController.blankToNull(queue), text, from, to, customerId);
        return staff.search(filter, page, size).map(p -> new SupportResponses.StaffPage(p.items().stream().map(SupportResponses::summary).toList(),
            p.total(), p.page(), p.size()));
    }

    @GetMapping("/cases/{ref}")
    @Operation(summary = "A case with internal notes, evidence, priority reasons and the full history")
    public Mono<SupportResponses.Staff> get(@PathVariable String ref) {
        return staff.get(ref).map(SupportResponses::staff);
    }

    @PostMapping("/cases/{ref}/assign")
    @Operation(summary = "Take a case and/or set its queue",
        description = "Taking an escalated case is what changes escalation.state from REQUESTED to ACCEPTED, and an OPEN case starts INVESTIGATING.")
    public Mono<SupportResponses.Staff> assign(@PathVariable String ref, @Valid @RequestBody SupportRequests.AssignRequest r, Authentication a) {
        return staff.assign(ref, userId(a), r.queue(), r.takeIt(), r.expectedVersion()).map(SupportResponses::staff);
    }

    @PostMapping("/cases/{ref}/priority")
    @Operation(summary = "Change the priority", description = "A reason is required; the change is added to the priority reasons and to the case history (staff-only).")
    public Mono<SupportResponses.Staff> priority(@PathVariable String ref, @Valid @RequestBody SupportRequests.PriorityRequest r, Authentication a) {
        CasePriority target;
        try {
            target = CasePriority.valueOf(r.priority().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Mono.error(new SupportDomainException(SupportErrorCode.VALIDATION_ERROR,
                "Unknown priority. Allowed: " + java.util.Arrays.toString(CasePriority.values())));
        }
        return staff.changePriority(ref, userId(a), target, r.reason(), r.expectedVersion()).map(SupportResponses::staff);
    }

    @PostMapping("/cases/{ref}/notes")
    @Operation(summary = "Add an internal note (never visible to the customer)")
    public Mono<SupportResponses.Staff> note(@PathVariable String ref, @Valid @RequestBody SupportRequests.MessageRequest r, Authentication a) {
        return staff.addNote(ref, userId(a), r.body(), r.expectedVersion()).map(SupportResponses::staff);
    }

    @PostMapping("/cases/{ref}/replies")
    @Operation(summary = "Reply to the customer (visible on their case)")
    public Mono<SupportResponses.Staff> reply(@PathVariable String ref, @Valid @RequestBody SupportRequests.MessageRequest r, Authentication a) {
        return staff.reply(ref, userId(a), r.body(), r.expectedVersion()).map(SupportResponses::staff);
    }

    @PostMapping("/cases/{ref}/status")
    @Operation(summary = "Change the status", description = "Validated against the lifecycle. Use /resolve to resolve. RESOLVED -> INVESTIGATING reopens.")
    public Mono<SupportResponses.Staff> status(@PathVariable String ref, @Valid @RequestBody SupportRequests.StatusRequest r, Authentication a) {
        return staff.changeStatus(ref, userId(a), parseStatus(r.status()), r.note(), r.expectedVersion()).map(SupportResponses::staff);
    }

    @PostMapping("/cases/{ref}/resolve")
    @Operation(summary = "Resolve a case with an outcome and a note",
        description = "Recording an outcome does not move money or change an order; refunds and returns use their own flows.")
    public Mono<SupportResponses.Staff> resolve(@PathVariable String ref, @Valid @RequestBody SupportRequests.ResolveRequest r, Authentication a) {
        ResolutionOutcome outcome;
        try {
            outcome = ResolutionOutcome.valueOf(r.outcome().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Mono.error(new SupportDomainException(SupportErrorCode.VALIDATION_ERROR,
                "Unknown outcome. Allowed: " + java.util.Arrays.toString(ResolutionOutcome.values())));
        }
        return staff.resolve(ref, userId(a), outcome, r.note(), r.evidenceRefs(), r.expectedVersion()).map(SupportResponses::staff);
    }

    @GetMapping("/report")
    @Operation(summary = "Ticket report for a period",
        description = "range = today | yesterday | last7 | last30 | custom (custom needs from and to as dates, to inclusive, at most 366 days). Days are counted in the "
            + "business time zone, which the response states. `definitions` explains each number: period metrics count events inside the period, the ...Now metrics describe the "
            + "current state, and createdInPeriodStillUnresolved is the period's cohort that is still unresolved.")
    public Mono<Map<String, Object>> report(@RequestParam(defaultValue = "last7") String range,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        var today = LocalDate.now(businessZone);
        LocalDate first;
        LocalDate last;
        switch (range.toLowerCase(Locale.ROOT)) {
            case "today" -> { first = today; last = today; }
            case "yesterday" -> { first = today.minusDays(1); last = first; }
            case "last7" -> { first = today.minusDays(6); last = today; }
            case "last30" -> { first = today.minusDays(29); last = today; }
            case "custom" -> {
                if (from == null || to == null) {
                    return Mono.error(new SupportDomainException(SupportErrorCode.VALIDATION_ERROR, "A custom range needs both from and to (YYYY-MM-DD)"));
                }
                first = from;
                last = to;
            }
            default -> {
                return Mono.error(new SupportDomainException(SupportErrorCode.VALIDATION_ERROR, "Unknown range. Allowed: today, yesterday, last7, last30, custom"));
            }
        }
        if (last.isBefore(first) || java.time.temporal.ChronoUnit.DAYS.between(first, last) > 365) {
            return Mono.error(new SupportDomainException(SupportErrorCode.VALIDATION_ERROR, "The range must end on or after its start and span at most 366 days"));
        }
        var fromInstant = first.atStartOfDay(businessZone).toInstant();
        var toInstant = last.plusDays(1).atStartOfDay(businessZone).toInstant();
        return staff.report(fromInstant, toInstant, businessZone.getId()).map(r -> {
            var out = new LinkedHashMap<String, Object>();
            out.put("range", range.toLowerCase(Locale.ROOT));
            out.put("from", first.toString());
            out.put("to", last.toString());
            out.put("timeZone", r.zone());
            out.put("createdInPeriod", r.createdInPeriod());
            out.put("resolvedInPeriod", r.resolvedInPeriod());
            out.put("reopenedInPeriod", r.reopenedInPeriod());
            out.put("escalatedInPeriod", r.escalatedInPeriod());
            out.put("createdInPeriodStillUnresolved", r.createdInPeriodStillUnresolved());
            out.put("openNow", r.openNow());
            out.put("inProgressNow", r.inProgressNow());
            out.put("unresolvedNow", r.unresolvedNow());
            out.put("unassignedNow", r.unassignedNow());
            out.put("escalationPendingNow", r.escalationPendingNow());
            out.put("createdByCategory", r.createdByCategory());
            out.put("createdByPriority", r.createdByPriority());
            out.put("daily", r.daily().stream().map(d -> Map.of("date", d.date().toString(), "created", d.created(), "resolved", d.resolved())).toList());
            out.put("oldestUnresolvedAgeHours", r.oldestUnresolvedAgeHours());
            out.put("slaNote", "No SLA is defined, so no breach is reported. oldestUnresolvedAgeHours is simply the age of the oldest unresolved ticket.");
            var defs = new LinkedHashMap<String, String>();
            defs.put("createdInPeriod", "Tickets whose creation time falls inside the selected days (created_at).");
            defs.put("resolvedInPeriod", "Tickets whose latest resolution time falls inside the selected days (resolved_at); a ticket reopened later has no resolution time until it is resolved again.");
            defs.put("reopenedInPeriod", "Distinct tickets with a REOPENED history event inside the selected days.");
            defs.put("escalatedInPeriod", "Distinct tickets with a customer escalation REQUEST inside the selected days (a request is not an accepted escalation).");
            defs.put("createdInPeriodStillUnresolved", "Of the tickets created in the period, how many are unresolved right now.");
            defs.put("openNow", "Tickets in status OPEN now (nobody has started).");
            defs.put("inProgressNow", "Tickets INVESTIGATING or waiting for the customer or an external party now.");
            defs.put("unresolvedNow", "All tickets not RESOLVED now: openNow + inProgressNow.");
            defs.put("unassignedNow", "Unresolved tickets with nobody assigned.");
            defs.put("escalationPendingNow", "Unresolved tickets where a person was requested and nobody has accepted yet.");
            out.put("definitions", defs);
            return out;
        });
    }

    @GetMapping("/analytics")
    @Operation(summary = "Support analytics derived from the persisted cases", description = "definitions explains exactly what each number counts.")
    public Mono<Map<String, Object>> analytics() {
        return staff.analytics().map(a -> {
            var out = new LinkedHashMap<String, Object>();
            out.put("totalCases", a.totalCases());
            out.put("unresolvedCases", a.unresolvedCases());
            out.put("byStatus", a.byStatus());
            out.put("byCategory", a.byCategory());
            out.put("unresolvedByPriority", a.byPriority());
            out.put("escalationRequestedNotYetAccepted", a.escalationRequestedNotAccepted());
            out.put("escalationAccepted", a.escalationAccepted());
            out.put("unassignedUnresolved", a.unassignedUnresolved());
            out.put("resolvedByOutcome", a.resolvedByOutcome());
            out.put("resolvedByHuman", a.resolvedByHuman());
            out.put("automaticallyResolved", 0);
            out.put("reopenedCases", a.reopenedCases());
            out.put("casesWithRepeatContact", a.casesWithRepeatContact());
            out.put("averageUnresolvedAgeHours", a.averageUnresolvedAgeHours());
            out.put("oldestUnresolvedAgeHours", a.oldestUnresolvedAgeHours());
            out.put("createdByAssistant", a.createdByAssistant());
            out.put("createdByWeb", a.createdByWeb());
            out.put("definitions", Map.ofEntries(
                Map.entry("unresolvedCases", "Cases whose status is not RESOLVED."),
                Map.entry("escalationRequestedNotYetAccepted", "Unresolved cases where a human was asked for but no support executive has taken the case yet."),
                Map.entry("automaticallyResolved", "Always 0 in this POC: only a human support executive can resolve a case. Questions the assistant answers without opening a case are counted in the AI service usage log, not here."),
                Map.entry("casesWithRepeatContact", "Cases whose customer has more than one case about the same order (including this one)."),
                Map.entry("averageUnresolvedAgeHours", "Mean hours since creation of unresolved cases; null when there are none. No SLA is defined, so none is measured."),
                Map.entry("createdByAssistant", "Cases the customer opened through the AI assistant (informational label, not a trust signal)."),
                Map.entry("dataNote", "Counts come from the support_cases table. Demo seed cases, if any, are labelled in their subject.")));
            return out;
        });
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private static CaseStatus parseStatus(String raw) {
        try {
            return CaseStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SupportDomainException(SupportErrorCode.VALIDATION_ERROR, "Unknown status. Allowed: " + java.util.Arrays.toString(CaseStatus.values()));
        }
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getPrincipal().toString());
    }
}
