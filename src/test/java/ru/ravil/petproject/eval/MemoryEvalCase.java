package ru.ravil.petproject.eval;

import java.util.ArrayList;
import java.util.List;

public class MemoryEvalCase {

    private String id;
    private String category;
    private List<String> save = new ArrayList<>();
    private List<MemoryEvalQuestion> questions = new ArrayList<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public List<String> getSave() {
        return save;
    }

    public void setSave(List<String> save) {
        this.save = save == null ? new ArrayList<>() : save;
    }

    public List<MemoryEvalQuestion> getQuestions() {
        return questions;
    }

    public void setQuestions(List<MemoryEvalQuestion> questions) {
        this.questions = questions == null ? new ArrayList<>() : questions;
    }
}
