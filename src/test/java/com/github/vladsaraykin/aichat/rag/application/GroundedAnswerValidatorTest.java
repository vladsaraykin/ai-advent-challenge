package com.github.vladsaraykin.aichat.rag.application;

import com.github.vladsaraykin.aichat.rag.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GroundedAnswerValidatorTest {
    final GroundedAnswerValidator validator=new GroundedAnswerValidator();
    final List<RagQuestion.Source> sources=List.of(new RagQuestion.Source(1,
            new DocumentChunk(UUID.randomUUID(),0,"test.pdf","Title","Section",2,2,0,40,
                    "Требования сохраняются в файле progress.md.",10),.8));
    @Test void validatesExactQuoteAndWhitespaceWithoutTrustingMetadata() {
        var answer=validator.validate("""
                {"status":"ANSWERED","answer":"Используется progress.md. [1]",
                 "quotes":[{"sourceNumber":1,"quote":"Требования  сохраняются в файле progress.md."}]}
                """,sources);
        assertThat(answer.grounding().status()).isEqualTo("VERIFIED");
        assertThat(answer.grounding().quotes()).hasSize(1);
    }
    @Test void rejectsInventedQuotesMissingCitationsForeignSourcesAndInvalidJson() {
        for(String raw:List.of("not json",
                "{\"status\":\"ANSWERED\",\"answer\":\"Ответ [1]\",\"quotes\":[]}",
                payload("Ответ [1]",1,"Полностью придуманный фрагмент"),
                payload("Ответ [2]",1,"Требования сохраняются в файле progress.md."),
                payload("Ответ [1]",2,"Требования сохраняются в файле progress.md."),
                payload("Ответ без ссылки",1,"Требования сохраняются в файле progress.md."))) {
            assertThatThrownBy(() -> validator.validate(raw,sources)).isInstanceOf(GroundedAnswerValidator.Failure.class);
        }
    }
    @Test void refusesEvenWhenRetrievedSourcesExist() {
        var result=validator.validate("{\"status\":\"INSUFFICIENT_CONTEXT\",\"answer\":\"\",\"quotes\":[]}",sources);
        assertThat(result.text()).contains("Не знаю","Уточните");
        assertThat(result.grounding().quotes()).isEmpty();
    }
    @Test void rejectsTrailingJsonDuplicateFieldsAndWhitespaceQuotes() {
        String valid=payload("Ответ [1]",1,"Требования сохраняются в файле progress.md.");
        for(String raw:List.of(valid+" {}",valid.replace("\"status\":", "\"status\":\"ANSWERED\",\"status\":"),
                payload("Ответ [1]",1,"  ".repeat(10)))) {
            assertThatThrownBy(() -> validator.validate(raw,sources)).isInstanceOf(GroundedAnswerValidator.Failure.class);
        }
    }
    private String payload(String answer,int number,String quote) {
        return "{\"status\":\"ANSWERED\",\"answer\":\""+answer+"\",\"quotes\":[{\"sourceNumber\":"+number+",\"quote\":\""+quote+"\"}]}";
    }
}
