package com.climind.saas.pricing;

import java.util.List;

/** Respuestas inválidas en un cálculo estricto. Una simulación inválida no consume cupo. */
public class InvalidAnswersException extends RuntimeException {

    private final transient List<PriceBreakdown.AnswerIssue> issues;

    public InvalidAnswersException(List<PriceBreakdown.AnswerIssue> issues) {
        super("Respuestas inválidas: " + issues.size());
        this.issues = List.copyOf(issues);
    }

    public List<PriceBreakdown.AnswerIssue> issues() {
        return issues;
    }
}
