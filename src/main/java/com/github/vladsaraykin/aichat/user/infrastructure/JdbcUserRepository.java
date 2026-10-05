package com.github.vladsaraykin.aichat.user.infrastructure;

import com.github.vladsaraykin.aichat.agent.application.ChatFailure;
import com.github.vladsaraykin.aichat.user.application.UserRepository;
import com.github.vladsaraykin.aichat.user.domain.*;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcUserRepository implements UserRepository {
 private final JdbcTemplate jdbc;
 private final JsonMapper json=JsonMapper.builder().build();
 public JdbcUserRepository(@Qualifier("ragJdbcTemplate") JdbcTemplate jdbc) { this.jdbc=jdbc; }
 @Override public Optional<UserAccount> account(String username) {
  return jdbc.query("SELECT username,password_hash,created_at FROM rag.users WHERE username=?",
   (rs,n)->new UserAccount(rs.getString(1),rs.getString(2),rs.getTimestamp(3).toInstant()),username).stream().findFirst();
 }
 @Override public UserProfile profile(String username) {
  return jdbc.query("SELECT profile FROM rag.users WHERE username=?",
   (rs,n)->json.readValue(rs.getString(1),UserProfile.class),username).stream().findFirst()
   .orElseThrow(()->new ChatFailure(ChatFailure.Kind.NOT_FOUND,"Профиль пользователя не найден"));
 }
 @Override public UserProfile create(UserAccount account,UserProfile profile) {
  if(!account.username().equals(profile.username())) throw new IllegalArgumentException("Profile owner mismatch");
  try { jdbc.update("INSERT INTO rag.users(username,password_hash,created_at,profile,profile_version) VALUES(?,?,?,?::jsonb,?)",
   account.username(),account.passwordHash(),Timestamp.from(account.createdAt()),json.writeValueAsString(profile),profile.version()); }
  catch(DuplicateKeyException e) { throw new ChatFailure(ChatFailure.Kind.INVALID,"Пользователь с таким логином уже существует"); }
  return profile;
 }
 @Override public UserProfile saveProfile(String username,long version,UserProfile profile) {
  if(!username.equals(profile.username()) || profile.version()!=version+1)
   throw new ChatFailure(ChatFailure.Kind.INVALID,"Некорректная версия или владелец профиля");
  if(jdbc.update("UPDATE rag.users SET profile=?::jsonb,profile_version=? WHERE username=? AND profile_version=?",
   json.writeValueAsString(profile),profile.version(),username,version)!=1)
   throw new ChatFailure(ChatFailure.Kind.BUSY,"Профиль уже изменился. Обновите данные и повторите действие.");
  return profile;
 }
}
