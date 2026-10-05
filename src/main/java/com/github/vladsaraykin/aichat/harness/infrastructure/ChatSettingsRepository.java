package com.github.vladsaraykin.aichat.harness.infrastructure;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.harness.domain.ChatSettings;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class ChatSettingsRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json=JsonMapper.builder().build();
    private final ChatRepository chats;
    public ChatSettingsRepository(@Qualifier("ragJdbcTemplate") JdbcTemplate jdbc,ChatRepository chats) { this.jdbc=jdbc;this.chats=chats; }
    public ChatSettings get(String owner,String agent,UUID id) {
        var chat=chats.get(owner,agent,id);
        var settings=jdbc.query("SELECT payload FROM rag.chat_settings WHERE owner_username=? AND chat_id=?",
                (rs,n)->json.readValue(rs.getString(1),ChatSettings.class),owner,id).stream().findFirst();
        if(settings.isPresent()) return settings.get();
        if(chat.parentChatId()!=null) {
            var inherited=get(owner,agent,chat.parentChatId());
            return new ChatSettings(0,inherited.ragEnabled(),inherited.mcpServerIds(),inherited.retrieval());
        }
        return ChatSettings.defaults();
    }
    public ChatSettings save(String owner,String agent,UUID id,ChatSettings settings) {
        var chat=chats.get(owner,agent,id);
        if(chat.readOnly()) throw new ChatFailure(ChatFailure.Kind.INVALID,"Checkpoint доступен только для чтения");
        if(settings.version()!=get(owner,agent,id).version()) throw new ChatFailure(ChatFailure.Kind.BUSY,"Настройки уже изменились. Обновите чат.");
        var next=new ChatSettings(settings.version()+1,settings.ragEnabled(),settings.mcpServerIds(),settings.retrieval());
        int count=jdbc.update("""
                INSERT INTO rag.chat_settings(owner_username,chat_id,version,payload) VALUES(?,?,?,?::jsonb)
                ON CONFLICT(owner_username,chat_id) DO UPDATE SET version=excluded.version,payload=excluded.payload
                WHERE rag.chat_settings.version=?
                """,owner,id,next.version(),json.writeValueAsString(next),settings.version());
        if(count!=1) throw new ChatFailure(ChatFailure.Kind.BUSY,"Настройки уже изменились. Обновите чат.");
        return next;
    }
}
