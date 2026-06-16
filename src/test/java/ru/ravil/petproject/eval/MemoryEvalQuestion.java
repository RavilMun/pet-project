package ru.ravil.petproject.eval;

import java.util.ArrayList;
import java.util.List;

public class MemoryEvalQuestion {

    private String text;
    private List<String> expectedFacts = new ArrayList<>();
    private List<String> forbiddenFacts = new ArrayList<>();
    private boolean mustNotSayUnknown;
    private boolean mustSayUnknown;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public List<String> getExpectedFacts() {
        return expectedFacts;
    }

    public void setExpectedFacts(List<String> expectedFacts) {
        this.expectedFacts = expectedFacts == null ? new ArrayList<>() : expectedFacts;
    }

    public List<String> getForbiddenFacts() {
        return forbiddenFacts;
    }

    public void setForbiddenFacts(List<String> forbiddenFacts) {
        this.forbiddenFacts = forbiddenFacts == null ? new ArrayList<>() : forbiddenFacts;
    }

    public boolean isMustNotSayUnknown() {
        return mustNotSayUnknown;
    }

    public void setMustNotSayUnknown(boolean mustNotSayUnknown) {
        this.mustNotSayUnknown = mustNotSayUnknown;
    }

    public boolean isMustSayUnknown() {
        return mustSayUnknown;
    }

    public void setMustSayUnknown(boolean mustSayUnknown) {
        this.mustSayUnknown = mustSayUnknown;
    }
}
