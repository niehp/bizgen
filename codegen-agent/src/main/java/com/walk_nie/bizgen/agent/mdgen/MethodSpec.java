package com.walk_nie.bizgen.agent.mdgen;

import java.util.List;

/**
 * 方法概要 md から確定的に解析した強型別スペック（「事実」部分）。
 * クラス名・メソッド名・シグネチャ・生成モードは全て人が書いた仕様書から来る。
 * モデルが書き換えてはならない——なぜなら流程制御を確定させるため。
 *
 * コメントは日本語：「何をするか」「なぜそうするか」を簡潔に記す（以下同様）。
 *
 * @param packageName  パッケージ名（空でも可）
 * @param className    対象クラス名
 * @param methodName   対象メソッド名
 * @param returnType   戻り値型（例: List&lt;String&gt;）
 * @param parameters   引数宣言（例: List&lt;String&gt; payments）
 * @param summary      処理概要（LLM への意味入力）
 * @param allowedApis  呼び出し許可 API（ホワイトリスト）
 * @param acceptance   判定可能な受入基準
 * @param operation    生成モード（新規／追加／修正）
 * @param targetFile   既存ファイルパス（追加・修正のとき必須）
 */
public record MethodSpec(
        String packageName,
        String className,
        String methodName,
        String returnType,
        List<String> parameters,
        String summary,
        List<String> allowedApis,
        List<String> acceptance,
        Operation operation,
        String targetFile
) {}
