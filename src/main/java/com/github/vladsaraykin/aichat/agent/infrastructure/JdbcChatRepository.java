package com.github.vladsaraykin.aichat.agent.infrastructure;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Task memory and conversation are stored separately; checkpoints are saved atomically. */
@Repository
public class JdbcChatRepository implements ChatRepository {
 private final JdbcTemplate jdbc;
 private final TransactionTemplate transactions;
 private final JsonMapper json=JsonMapper.builder().build();
 public JdbcChatRepository(@Qualifier("ragJdbcTemplate") JdbcTemplate jdbc,@Qualifier("ragTransactions") TransactionTemplate transactions) {
  this.jdbc=jdbc;this.transactions=transactions;
 }
 @Override public List<Chat> list(String owner,String agent) {
  return jdbc.query("SELECT payload,working_memory FROM rag.chats WHERE owner_username=? AND agent_id=? ORDER BY updated_at DESC",
   (rs,n)->restore(json.readValue(rs.getString(1),Chat.class),json.readValue(rs.getString(2),WorkingMemory.class)),owner,agent);
 }
 @Override public Chat get(String owner,String agent,UUID id) {
  var chats=list(owner,agent);
  var chat=chats.stream().filter(c->c.id().equals(id)).findFirst()
   .orElseThrow(()->new ChatFailure(ChatFailure.Kind.NOT_FOUND,"Чат не найден"));
  return children(chat,chats);
 }
 private Chat children(Chat chat,List<Chat> chats) {
  return chat.withBranches(chats.stream().filter(c->chat.id().equals(c.parentChatId())).map(c->children(c,chats)).toList());
 }
 @Override public void save(String owner,Chat chat) { transactions.executeWithoutResult(tx->saveTree(owner,chat)); }
 private void saveTree(String owner,Chat chat) {
  var payload=restore(chat.withBranches(List.of()),WorkingMemory.empty());
  int rows=jdbc.update("""
   INSERT INTO rag.chats(owner_username,id,agent_id,parent_id,payload,working_memory,updated_at)
   VALUES(?,?,?,?,?::jsonb,?::jsonb,?) ON CONFLICT(owner_username,id) DO UPDATE
   SET payload=excluded.payload,working_memory=excluded.working_memory,updated_at=excluded.updated_at
   WHERE rag.chats.agent_id=excluded.agent_id AND rag.chats.parent_id IS NOT DISTINCT FROM excluded.parent_id
   """,owner,chat.id(),chat.agentId(),chat.parentChatId(),json.writeValueAsString(payload),
   json.writeValueAsString(chat.workingMemory()),Timestamp.from(chat.updatedAt()));
  if(rows!=1) throw new ChatFailure(ChatFailure.Kind.INVALID,"Нельзя изменить владельца или родителя чата");
  if(chat.parentChatId()!=null) jdbc.update("""
   INSERT INTO rag.chat_message_archive(owner_username,chat_id,message_id,created_at,payload,position)
   SELECT owner_username,?,message_id,created_at,jsonb_set(payload,'{metrics}','null'::jsonb),position
   FROM rag.chat_message_archive WHERE owner_username=? AND chat_id=?
   ON CONFLICT(owner_username,chat_id,message_id) DO NOTHING
   """,chat.id(),owner,chat.parentChatId());
  long position=jdbc.queryForObject("SELECT coalesce(max(position),-1)+1 FROM rag.chat_message_archive WHERE owner_username=? AND chat_id=?",Long.class,owner,chat.id());
  for(var message:chat.messages()) {
   int inserted=jdbc.update("""
    INSERT INTO rag.chat_message_archive(owner_username,chat_id,message_id,created_at,payload,position)
    VALUES(?,?,?,?,?::jsonb,?) ON CONFLICT(owner_username,chat_id,message_id) DO NOTHING
    """,owner,chat.id(),message.id(),Timestamp.from(message.createdAt()),json.writeValueAsString(message),position);
   if(inserted==1) position++;
  }
  for(var child:chat.branches()) saveTree(owner,child);
 }
 public List<ChatMessage> history(String owner,String agent,UUID id) {
  get(owner,agent,id);
  return jdbc.query("SELECT payload FROM rag.chat_message_archive WHERE owner_username=? AND chat_id=? ORDER BY position",
   (rs,n)->json.readValue(rs.getString(1),ChatMessage.class),owner,id);
 }
 @Override public void delete(String owner,String agent,UUID id) {
  transactions.executeWithoutResult(tx -> {
   Integer running=jdbc.queryForObject("""
    WITH RECURSIVE affected AS (
     SELECT id FROM rag.chats WHERE owner_username=? AND agent_id=? AND id=?
     UNION ALL
     SELECT c.id FROM rag.chats c JOIN affected a ON c.parent_id=a.id WHERE c.owner_username=?
    ) SELECT count(*) FROM rag.harness_requests r JOIN affected a ON r.chat_id=a.id
      WHERE r.owner_username=? AND r.status='RUNNING'
    """,Integer.class,owner,agent,id,owner,owner);
   if(running!=null && running>0)
    throw new ChatFailure(ChatFailure.Kind.BUSY,"Нельзя удалить чат: выполняется запрос в нём или его ветке.");
   if(jdbc.update("DELETE FROM rag.chats WHERE owner_username=? AND agent_id=? AND id=?",owner,agent,id)!=1)
    throw new ChatFailure(ChatFailure.Kind.NOT_FOUND,"Чат не найден");
  });
 }
 private static Chat restore(Chat c,WorkingMemory memory) {
  return new Chat(c.id(),c.agentId(),c.title(),c.createdAt(),c.updatedAt(),c.summary(),c.messages(),c.strategy(),
   c.memory(),c.parentChatId(),c.checkpointId(),c.branches(),c.readOnly(),memory,c.invariants(),c.lifecycle());
 }
}
