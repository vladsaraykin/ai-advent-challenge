package com.github.vladsaraykin.aichat.rag.application;

import com.github.vladsaraykin.aichat.rag.domain.*;
import com.github.vladsaraykin.aichat.user.application.UserRepository;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

public final class RagQuestionService implements AutoCloseable {
    public record Request(UUID id, UUID indexId, String question, RagQuestion.Mode mode, RetrievalOptions retrievalOptions) {
        public Request(UUID id, UUID indexId, String question, RagQuestion.Mode mode) { this(id,indexId,question,mode,null); }
    }
    private final Reranker reranker;
    private final RetrievalOptions defaults;
    private final IndexRepository indexes;
    private final RagQuestionRepository repository;
    private final EmbeddingModel embeddings;
    private final String embeddingModel;
    private final RagAnswerModel llm;
    private final RagAnswerSettings settings;
    private final ChunkTokenEstimator tokens;
    private final UserRepository users;
    private final JsonMapper json = JsonMapper.builder().build();
    private final GroundedAnswerValidator grounding = new GroundedAnswerValidator();
    private final ExecutorService executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Thread.ofPlatform().name("rag-question-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());

    public RagQuestionService(IndexRepository indexes, RagQuestionRepository repository, EmbeddingModel embeddings,
            String embeddingModel, RagAnswerModel llm, RagAnswerSettings settings, ChunkTokenEstimator tokens, UserRepository users) {
        this(indexes,repository,embeddings,embeddingModel,llm,settings,tokens,users,
                (question,documents) -> { throw new Reranker.Failure(); },new RetrievalOptions(20,5,.2));
    }
    public RagQuestionService(IndexRepository indexes, RagQuestionRepository repository, EmbeddingModel embeddings,
            String embeddingModel, RagAnswerModel llm, RagAnswerSettings settings, ChunkTokenEstimator tokens, UserRepository users,
            Reranker reranker, RetrievalOptions defaults) {
        this.reranker=reranker; this.defaults=defaults;
        this.indexes=indexes; this.repository=repository; this.embeddings=embeddings; this.embeddingModel=embeddingModel;
        this.llm=llm; this.settings=settings; this.tokens=tokens; this.users=users;
        repository.recoverInterrupted();
    }
    public RagAnswerSettings settings() { return settings; }
    public RetrievalOptions retrievalDefaults() { return defaults; }
    public List<RagQuestion> list(String owner, UUID index) { indexes.get(owner, index); return repository.list(owner,index); }

    public void start(String owner, Request request, BiConsumer<String,Object> events, BooleanSupplier cancelled) {
        if (request == null || request.id()==null || request.indexId()==null || request.mode()==null
                || request.question()==null || request.question().isBlank() || request.question().length()>4000) {
            throw new RagFailure(RagFailure.Kind.INVALID,"Вопрос должен содержать от 1 до 4000 символов; укажите индекс и режим.");
        }
        if (settings.model().isBlank()) throw new RagFailure(RagFailure.Kind.INVALID,"Настройте подтверждённый API ID GPT-6.1 Sol в RAG_ANSWER_MODEL.");
        var index = indexes.get(owner, request.indexId());
        if (!index.status().equals("COMPLETED")) throw new RagFailure(RagFailure.Kind.INVALID,"Выберите завершённый индекс.");
        if (request.mode()!=RagQuestion.Mode.WITHOUT_RAG
                && (!canonical(index.embeddingModel()).equals(canonical(embeddingModel)) || index.dimensions()!=768)) {
            throw new RagFailure(RagFailure.Kind.INVALID,"Модель эмбеддингов отличается от модели индекса. Переключите Ollama или переиндексируйте документ.");
        }
        if (request.retrievalOptions()==null && advanced(request.mode())) request = new Request(request.id(),request.indexId(),request.question(),request.mode(),defaults);
        var normalized = request;
        var profile = users.profile(owner); // Snapshot identical in all modes; no dialogue history.
        String profileText = json.writeValueAsString(Map.of("style",profile.responseStyle(),"format",profile.responseFormat(),"constraints",profile.constraints()));
        try { executor.execute(() -> execute(owner, normalized, profileText, events, cancelled)); }
        catch (RejectedExecutionException error) { throw new RagFailure(RagFailure.Kind.BUSY,"Уже выполняются два RAG-запроса. Повторите позже."); }
    }

    private void execute(String owner, Request request, String profile, BiConsumer<String,Object> events, BooleanSupplier cancelled) {
        var answers = new ArrayList<RagQuestion.Answer>();
        var created = Instant.now();
        boolean inserted = false;
        boolean finished = false;
        try {
            var initial = new RagQuestion(request.id(),request.indexId(),request.question(),request.mode(),"RUNNING",List.of(),created,request.retrievalOptions());
            inserted = repository.create(owner, initial);
            if (!inserted) {
                var prior = repository.find(owner,request.id()).orElseThrow(() -> new IllegalArgumentException("Missing"));
                if (!prior.indexId().equals(request.indexId()) || !prior.question().equals(request.question()) || prior.mode()!=request.mode()
                        || !Objects.equals(prior.retrievalOptions(),request.retrievalOptions())) {
                    throw new IllegalArgumentException("Request ID reused");
                }
                if (prior.status().equals("RUNNING")) throw new IllegalStateException("Already running");
                events.accept("completed",prior); return;
            }
            var modes = request.mode()==RagQuestion.Mode.BOTH ? List.of(RagQuestion.Mode.WITHOUT_RAG,RagQuestion.Mode.WITH_RAG)
                    : request.mode()==RagQuestion.Mode.COMPARE ? List.of(RagQuestion.Mode.WITH_RAG,RagQuestion.Mode.RERANKED,RagQuestion.Mode.REWRITTEN) : List.of(request.mode());
            for (var mode : modes) {
                if (cancelled.getAsBoolean()) throw new IllegalStateException("Disconnected");
                long embeddingMs=0, searchMs=0; Long embeddingTokens=null;
                List<RagQuestion.Source> sources = new ArrayList<>();
                RagQuestion.Retrieval retrieval=null;
                RagQuestion.Metrics generationMetrics=null;
                try {
                    if (mode!=RagQuestion.Mode.WITHOUT_RAG) {
                        var options=advanced(request.mode()) ? request.retrievalOptions() : new RetrievalOptions(settings.topK(),settings.topK(),0);
                        String searchQuery=request.question();
                        RagQuestion.Metrics rewriteMetrics=null;
                        if (mode==RagQuestion.Mode.REWRITTEN) {
                            events.accept("phase",Map.of("mode",mode,"phase","Переформулирование вопроса"));
                            var rewrite=llm.rewrite(request.question());
                            rewriteMetrics=rewrite.metrics();
                            searchQuery=rewrite.text().strip();
                            retrieval=new RagQuestion.Retrieval(searchQuery,0,rewriteMetrics,options,List.of());
                            if (searchQuery.isBlank() || searchQuery.length()>4000 || !"stop".equals(rewriteMetrics.finishReason())) {
                                throw new IllegalStateException("Incomplete rewrite");
                            }
                        }
                        events.accept("phase",Map.of("mode",mode,"phase","Поиск источников"));
                        long started=System.nanoTime();
                        var vector=embeddings.embed(searchQuery);
                        embeddingMs=elapsed(started); embeddingTokens=vector.promptTokens(); started=System.nanoTime();
                        int candidateK=request.mode()==RagQuestion.Mode.COMPARE || advanced(mode) ? options.candidateK() : settings.topK();
                        var candidates=repository.search(owner,request.indexId(),vector.vector(),candidateK);
                        searchMs=elapsed(started);
                        retrieval=new RagQuestion.Retrieval(searchQuery,0,rewriteMetrics,options,
                                candidates.stream().map(s -> new RagQuestion.Candidate(s,null,"NOT_EVALUATED")).toList());
                        long rerankingMs=0;
                        List<Double> scores=null;
                        if (advanced(mode) && !candidates.isEmpty()) {
                            events.accept("phase",Map.of("mode",mode,"phase","Реранкинг и фильтрация"));
                            started=System.nanoTime();
                            // Rank against the original question; rewrite only affects retrieval.
                            scores=reranker.score(request.question(),candidates.stream().map(s -> s.chunk().content()).toList());
                            rerankingMs=elapsed(started);
                            validateScores(scores,candidates.size());
                        }
                        var selection=rank(candidates,scores,advanced(mode),options,
                                request.mode()==RagQuestion.Mode.COMPARE || advanced(mode) ? options.finalK() : settings.topK());
                        sources=selection.sources();
                        retrieval=new RagQuestion.Retrieval(searchQuery,rerankingMs,rewriteMetrics,options,selection.candidates());
                        LoggerFactory.getLogger(getClass()).info("rag_retrieval_completed requestId={} mode={} candidates={} selected={} embeddingMs={} searchMs={} rerankingMs={} rewritten={}",
                                request.id(),mode,candidates.size(),sources.size(),embeddingMs,searchMs,rerankingMs,rewriteMetrics!=null);
                        events.accept("retrieval",Map.of("mode",mode,"retrieval",retrieval));
                        events.accept("sources",Map.of("mode",mode,"sources",sources));
                    }
                    if (mode!=RagQuestion.Mode.WITHOUT_RAG && sources.isEmpty()) {
                        var unknown=GroundedAnswerValidator.unknown("NO_ELIGIBLE_CONTEXT");
                        var skipped=new RagQuestion.Metrics(settings.model(),0,embeddingMs,searchMs,embeddingTokens,
                                0,0,0,0,java.math.BigDecimal.ZERO,"not_called");
                        answers.add(new RagQuestion.Answer(mode,unknown.text(),List.of(),skipped,null,retrieval,unknown.grounding()));
                    } else {
                        events.accept("phase",Map.of("mode",mode,"phase","Генерация ответа"));
                        String system = "Ты отвечаешь на независимый вопрос. Не используй историю других запросов. "
                                + "Профиль задаёт предпочтения, явные указания вопроса важнее: " + profile + "\n"
                                + (mode!=RagQuestion.Mode.WITHOUT_RAG
                                ? GroundedAnswerValidator.INSTRUCTION
                                : "Ответь по своим знаниям. Документ и источники не предоставлены; не утверждай, что ты их прочитал, и не придумывай ссылки [N].");
                        String input = mode!=RagQuestion.Mode.WITHOUT_RAG ? json.writeValueAsString(Map.of("sources",sources,"question",request.question())) : request.question();
                        var result = llm.answer(system,input,delta -> {
                            if (cancelled.getAsBoolean()) throw new IllegalStateException("Disconnected");
                            // Structured RAG output is withheld until its citations pass validation.
                            if (mode==RagQuestion.Mode.WITHOUT_RAG) events.accept("delta",Map.of("mode",mode,"text",delta));
                        });
                        var m=result.metrics();
                        var metrics=new RagQuestion.Metrics(m.model(),m.generationMs(),embeddingMs,searchMs,embeddingTokens,
                                m.promptTokens(),m.completionTokens(),m.totalTokens(),m.cachedPromptTokens(),m.costUsd(),m.finishReason());
                        generationMetrics=metrics;
                        if (mode==RagQuestion.Mode.WITHOUT_RAG) {
                            answers.add(new RagQuestion.Answer(mode,result.text(),List.copyOf(sources),metrics,null,retrieval));
                        } else {
                            events.accept("phase",Map.of("mode",mode,"phase","Проверка источников и цитат"));
                            if (!"stop".equals(m.finishReason())) throw new GroundedAnswerValidator.Failure();
                            var verified=grounding.validate(result.text(),sources);
                            answers.add(new RagQuestion.Answer(mode,verified.text(),List.copyOf(sources),metrics,null,retrieval,verified.grounding()));
                        }
                    }
                } catch (RuntimeException error) {
                    String message = error instanceof Reranker.Failure ? error.getMessage()
                            : error instanceof GroundedAnswerValidator.Failure ? error.getMessage()
                            : error instanceof EmbeddingModel.Failure ? "Не удалось получить embedding вопроса. Проверьте Ollama." : "Не удалось получить полный ответ. Проверьте модель, доступ провайдера и серверные журналы.";
                    answers.add(new RagQuestion.Answer(mode,"",List.copyOf(sources),generationMetrics,message,retrieval));
                    LoggerFactory.getLogger(getClass()).warn("rag_answer_failed requestId={} mode={} errorType={}",request.id(),mode,error.getClass().getSimpleName());
                }
                repository.save(owner,new RagQuestion(request.id(),request.indexId(),request.question(),request.mode(),"RUNNING",List.copyOf(answers),created,request.retrievalOptions()));
                events.accept("answer",answers.getLast());
            }
            long successes=answers.stream().filter(answer -> answer.error()==null).count();
            var result=new RagQuestion(request.id(),request.indexId(),request.question(),request.mode(),
                    successes==answers.size()?"COMPLETED":successes==0?"FAILED":"PARTIAL",List.copyOf(answers),created,request.retrievalOptions());
            repository.save(owner,result); finished = true; events.accept("completed",result);
            LoggerFactory.getLogger(getClass()).info("rag_question_completed requestId={} indexId={} mode={} status={}",request.id(),request.indexId(),request.mode(),result.status());
        } catch (RuntimeException error) {
            if (inserted && !finished) try { repository.save(owner,new RagQuestion(request.id(),request.indexId(),request.question(),request.mode(),"INTERRUPTED",List.copyOf(answers),created,request.retrievalOptions())); } catch (RuntimeException ignored) { }
            try { events.accept("error",Map.of("message","Запрос не завершён. Обновите историю результатов перед новой попыткой.")); } catch (RuntimeException ignored) { }
        }
    }

    List<RagQuestion.Source> select(List<RagQuestion.Source> candidates) {
        return rank(candidates,null,false,new RetrievalOptions(settings.topK(),settings.topK(),0),settings.topK()).sources();
    }
    private static boolean advanced(RagQuestion.Mode mode) {
        return mode==RagQuestion.Mode.RERANKED || mode==RagQuestion.Mode.REWRITTEN || mode==RagQuestion.Mode.COMPARE;
    }
    private static void validateScores(List<Double> scores,int count) {
        if (scores==null || scores.size()!=count || scores.stream().anyMatch(s -> s==null || !Double.isFinite(s) || s<0 || s>1)) throw new Reranker.Failure();
    }
    record Selection(List<RagQuestion.Source> sources,List<RagQuestion.Candidate> candidates) { }
    Selection rank(List<RagQuestion.Source> candidates,List<Double> scores,boolean rerank,RetrievalOptions options,int finalK) {
        var order=new ArrayList<Integer>();
        for(int i=0;i<candidates.size();i++) order.add(i);
        if(rerank && scores!=null) order.sort(Comparator.<Integer>comparingDouble(scores::get).reversed().thenComparingInt(i -> i));
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
                var tentative=new ArrayList<>(selected); tentative.add(next);
                if(tokens.count(json.writeValueAsString(tentative))>settings.maxContextTokens()) reason="BUDGET";
                else selected.add(next);
            }
            decisions.put(i,reason);
        }
        var trace=new ArrayList<RagQuestion.Candidate>();
        for(int i=0;i<candidates.size();i++) trace.add(new RagQuestion.Candidate(candidates.get(i),scores==null?null:scores.get(i),decisions.get(i)));
        return new Selection(List.copyOf(selected),List.copyOf(trace));
    }
    private String canonical(String model) { return model.endsWith(":latest") ? model.substring(0,model.length()-7) : model; }
    private long elapsed(long started) { return (System.nanoTime()-started)/1_000_000; }
    @Override public void close() {
        executor.shutdownNow();
        try { executor.awaitTermination(5,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
