package com.walk_nie.bizgen.agent.mdgen;

/**
 * 生成モード。既定値は新規ファイル生成——
 * まずは既存資産を壊さない形を優先するため、追加・修正は明示的に指定させる。
 */
public enum Operation {
    NEW_FILE,        // 新規 Java ファイルを作る
    ADD_METHOD,      // 既存ファイルにメソッドを追加する
    MODIFY_METHOD    // 既存ファイルのメソッドを差し替える
}
