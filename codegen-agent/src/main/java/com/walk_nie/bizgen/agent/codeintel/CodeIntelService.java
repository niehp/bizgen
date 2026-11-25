package com.walk_nie.bizgen.agent.codeintel;

import com.walk_nie.bizgen.agent.config.AgentConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * コード知能機能の共通エントリ。同じ DB パスなら同一インスタンスを使い回す。
 * LLM から呼ばれるツールと CLI が同じ索引・同じ提案状態を共有するためのもの。
 */
public final class CodeIntelService {

    private static final ConcurrentMap<String, CodeIntelService> CACHE =
            new ConcurrentHashMap<String, CodeIntelService>();

    private final AgentConfig config;
    private final JavaIndexStore store;
    private final JavaIndexer indexer;
    private final JavaSearchService search;
    private final JavaEditService edit;

    private CodeIntelService(AgentConfig config) {
        this.config = config;
        this.store = new JavaIndexStore(config.javaIndexDb());
        try {
            store.open();
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 索引を開けませんでした: " + e.getMessage(), e);
        }
        this.indexer = new JavaIndexer(config, store);
        this.search = new JavaSearchService(config, store);
        this.edit = new JavaEditService(config, store, indexer);
    }

    public static CodeIntelService forConfig(AgentConfig config) {
        String key = config.javaIndexDb().toAbsolutePath().normalize().toString();
        CodeIntelService existing = CACHE.get(key);
        if (existing != null) {
            return existing;
        }
        CodeIntelService created = new CodeIntelService(config);
        CodeIntelService raced = CACHE.putIfAbsent(key, created);
        return raced == null ? created : raced;
    }

    public JavaIndexStore store() {
        return store;
    }

    public JavaIndexer indexer() {
        return indexer;
    }

    public JavaSearchService search() {
        return search;
    }

    public JavaEditService edit() {
        return edit;
    }

    /** 索引が空なら一度だけ走査する。LLM ツールからも事前準備なしで検索できるようにする。 */
    public void ensureReady() {
        try {
            if (store.stats().startsWith("java_files=0")) {
                indexer.refresh();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("索引状態の確認に失敗: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalStateException("索引の初期化に失敗: " + e.getMessage(), e);
        }
    }

    public String stats() {
        try {
            return store.stats() + " db=" + store.dbPath().toAbsolutePath().normalize();
        } catch (SQLException e) {
            return "索引統計の取得に失敗: " + e.getMessage();
        }
    }

    public boolean dbExists() {
        return Files.exists(config.javaIndexDb());
    }
}
