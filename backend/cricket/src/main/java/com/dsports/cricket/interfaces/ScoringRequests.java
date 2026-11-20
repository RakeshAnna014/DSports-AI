package com.dsports.cricket.interfaces;

import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.rules.Delivery;
import com.dsports.cricket.domain.rules.Dismissal;
import com.dsports.cricket.domain.rules.InningsState;
import com.dsports.cricket.domain.rules.ScoringCommand;
import com.dsports.cricket.domain.scoring.ScoreCommand;
import com.dsports.cricket.domain.scoring.ScoringLog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Request bodies of the scoring API. One envelope carries a client event id and the seq the scorer last saw; the command is a flat body chosen by its {@code type}. */
public final class ScoringRequests {

    private ScoringRequests() {
    }

    public record DismissalBody(@NotNull Dismissal.Type type, UUID batterOut, Dismissal.End end, UUID fielderId) {
        Dismissal toDismissal() {
            return new Dismissal(type, batterOut, end, fielderId);
        }
    }

    public record DeliveryBody(Delivery.Kind kind, Integer runs, Delivery.RunsType runsType, Boolean boundary, @Valid DismissalBody dismissal) {
        Delivery toDelivery() {
            return new Delivery(kind == null ? Delivery.Kind.NORMAL : kind, runs == null ? 0 : runs, runsType, Boolean.TRUE.equals(boundary), dismissal == null ? null : dismissal.toDismissal());
        }
    }

    /**
     * START_INNINGS {striker, nonStriker, bowler}; DELIVER {delivery}; SELECT_BATTER {batterId}; SELECT_BOWLER and CHANGE_BOWLER {bowlerId};
     * RETIRE_BATTER {batterId, retirement}; REVISE_TARGET {target}; END_INNINGS {reason: DECLARED or NO_BATTERS_LEFT}; END_MATCH {reason: ABANDONED or NO_RESULT}.
     */
    public record CommandBody(@NotNull ScoreCommand.Type type, UUID striker, UUID nonStriker, UUID bowler, @Valid DeliveryBody delivery, UUID batterId, UUID bowlerId,
                              ScoringCommand.RetirementType retirement, Integer target, @Size(max = 40) String reason) {

        public ScoreCommand toCommand() {
            return switch (type) {
                case START_INNINGS -> new ScoreCommand.StartInnings(need(striker, "striker"), need(nonStriker, "nonStriker"), need(bowler, "bowler"));
                case DELIVER -> new ScoreCommand.Play(new ScoringCommand.Deliver(need(delivery, "delivery").toDelivery()));
                case SELECT_BATTER -> new ScoreCommand.Play(new ScoringCommand.SelectBatter(need(batterId, "batterId")));
                case SELECT_BOWLER -> new ScoreCommand.Play(new ScoringCommand.SelectBowler(need(bowlerId, "bowlerId")));
                case CHANGE_BOWLER -> new ScoreCommand.Play(new ScoringCommand.ChangeBowlerMidOver(need(bowlerId, "bowlerId")));
                case RETIRE_BATTER -> new ScoreCommand.Play(new ScoringCommand.RetireBatter(need(batterId, "batterId"), need(retirement, "retirement")));
                case REVISE_TARGET -> new ScoreCommand.Play(new ScoringCommand.ReviseTarget(need(target, "target")));
                case END_INNINGS -> new ScoreCommand.Play(new ScoringCommand.EndInnings(reasonOf(InningsState.EndReason.class)));
                case END_MATCH -> new ScoreCommand.EndMatch(reasonOf(ScoreCommand.EndMatch.Reason.class));
            };
        }

        private <E extends Enum<E>> E reasonOf(Class<E> enumType) {
            try {
                return Enum.valueOf(enumType, need(reason, "reason").trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new CricketDomainException(CricketErrorCode.VALIDATION_ERROR, "Unknown reason: " + reason);
            }
        }

        private static <T> T need(T value, String field) {
            if (value == null) {
                throw new CricketDomainException(CricketErrorCode.VALIDATION_ERROR, field + " is required for this command");
            }
            return value;
        }
    }

    public record CommandRequest(@NotNull UUID clientEventId, @NotNull @Min(0) Integer expectedSeq, @NotNull @Valid CommandBody command) {
    }

    /** {@code mode}: BALL (the default) takes off the last ball and what was chosen after it; COMMAND takes off just the latest command. */
    public record UndoRequest(@NotNull UUID clientEventId, @NotNull @Min(0) Integer expectedSeq, ScoringLog.UndoMode mode) {
        ScoringLog.UndoMode modeOrDefault() {
            return mode == null ? ScoringLog.UndoMode.BALL : mode;
        }
    }

    public record UnlockRequest(UUID clientEventId, @NotBlank @Size(max = 300) String reason) {
    }
}
