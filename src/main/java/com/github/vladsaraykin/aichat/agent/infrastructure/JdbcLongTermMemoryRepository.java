package com.github.vladsaraykin.aichat.agent.infrastructure;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.LongTermMemory;
import java.util.*;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcLongTermMemoryRepository implements LongTermMemoryRepository {
 private final JdbcTemplate jdbc;
 private final TransactionTemplate transactions;
 private final JsonMapper json=JsonMapper.builder().build();
 public JdbcLongTermMemoryRepository(@Qualifier("ragJdbcTemplate") JdbcTemplate jdbc,@Qualifier("ragTransactions") TransactionTemplate transactions) {
  this.jdbc=jdbc;this.transactions=transactions;
 }
 @Override public LongTermMemory get(String owner,String agent) {
  return jdbc.query("SELECT payload FROM rag.long_term_memory WHERE owner_username=? AND agent_id=?",
   (rs,n)->json.readValue(rs.getString(1),LongTermMemory.class),owner,agent).stream().findFirst().orElseGet(LongTermMemory::empty);
 }
 private LongTermMemory update(String owner,String agent,long version,UnaryOperator<LongTermMemory> change) {
  return transactions.execute(tx->{
   jdbc.update("INSERT INTO rag.long_term_memory(owner_username,agent_id,payload) VALUES(?,?,?::jsonb) ON CONFLICT DO NOTHING",
    owner,agent,json.writeValueAsString(LongTermMemory.empty()));
   var old=jdbc.queryForObject("SELECT payload FROM rag.long_term_memory WHERE owner_username=? AND agent_id=? FOR UPDATE",
    (rs,n)->json.readValue(rs.getString(1),LongTermMemory.class),owner,agent);
   MemoryService.checkVersion(old.version(),version);
   var next=change.apply(old);
   jdbc.update("UPDATE rag.long_term_memory SET payload=?::jsonb,version=? WHERE owner_username=? AND agent_id=?",
    json.writeValueAsString(next),next.version(),owner,agent);
   return next;
  });
 }
 @Override public LongTermMemory put(String owner,String agent,long version,LongTermMemory.Entry entry) {
  return update(owner,agent,version,old->{
   var entries=new ArrayList<>(old.entries());entries.removeIf(e->e.id().equals(entry.id()));
   if(entries.stream().anyMatch(e->e.scope()==entry.scope() && e.projectKey().equals(entry.projectKey()) && e.key().equals(entry.key())))
    throw new ChatFailure(ChatFailure.Kind.INVALID,"Такой ключ уже сохранён в этой области.");
   if(entries.size()>=100) throw new ChatFailure(ChatFailure.Kind.INVALID,"Достигнут лимит: 100 записей памяти.");
   entries.add(entry);var resolved=new HashSet<>(old.resolvedProposals());
   if(entry.sourceMessageId()!=null) resolved.add(entry.id());
   return new LongTermMemory(old.version()+1,entries,resolved);
  });
 }
 @Override public LongTermMemory delete(String owner,String agent,long version,UUID id) {
  return update(owner,agent,version,old->{
   if(old.entries().stream().noneMatch(e->e.id().equals(id))) throw new ChatFailure(ChatFailure.Kind.NOT_FOUND,"Запись не найдена");
   return new LongTermMemory(old.version()+1,old.entries().stream().filter(e->!e.id().equals(id)).toList(),old.resolvedProposals());
  });
 }
}
