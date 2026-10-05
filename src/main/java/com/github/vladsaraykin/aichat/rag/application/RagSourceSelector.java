package com.github.vladsaraykin.aichat.rag.application;

import com.github.vladsaraykin.aichat.rag.domain.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Pure selection shared by chat retrieval and the original experiment adapter. */
public final class RagSourceSelector {
    private final ChunkTokenEstimator tokens;
    private final int budget;
    private final JsonMapper json=JsonMapper.builder().build();
    public RagSourceSelector(ChunkTokenEstimator tokens,int budget) { this.tokens=tokens;this.budget=budget; }
    public record Selection(List<RagQuestion.Source> sources,List<RagQuestion.Candidate> candidates) { }
    public Selection rank(List<RagQuestion.Source> candidates,List<Double> scores,boolean rerank,RetrievalOptions options,int finalK) {
        var order=new ArrayList<Integer>();
        for(int i=0;i<candidates.size();i++) order.add(i);
        if(rerank && scores!=null) order.sort(Comparator.<Integer>comparingDouble(scores::get).reversed().thenComparingInt(i->i));
        var selected=new ArrayList<RagQuestion.Source>();
        var decisions=new HashMap<Integer,String>();
        for(int i:order) {
            var source=candidates.get(i);
            String reason="SELECTED";
            if(!Double.isFinite(source.similarity())) reason="INVALID";
            else if(rerank && scores!=null && scores.get(i)<options.threshold()) reason="THRESHOLD";
            else if(selected.size()>=finalK) reason="TOP_K";
            else {
                var next=new RagQuestion.Source(selected.size()+1,source.chunk(),source.similarity());
                var tentative=new ArrayList<>(selected);tentative.add(next);
                if(tokens.count(json.writeValueAsString(tentative))>budget) reason="BUDGET";
                else selected.add(next);
            }
            decisions.put(i,reason);
        }
        var trace=new ArrayList<RagQuestion.Candidate>();
        for(int i=0;i<candidates.size();i++) trace.add(new RagQuestion.Candidate(candidates.get(i),scores==null ? null : scores.get(i),decisions.get(i)));
        return new Selection(List.copyOf(selected),List.copyOf(trace));
    }
}
