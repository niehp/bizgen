# サービスクラスのテンプレート

新規クラスを作るときの骨格。{{applicationId}} のような変数はモデルへ渡す前に展開済み。

```java
package {{packageName}};

/**
 * {{className}} のサービス。
 *
 * <p>何をするクラスか、なぜ必要かを日本語で簡潔に記載する。</p>
 */
public class {{className}} {

    /**
     * {{methodPurpose1}}。
     *
     * <p>なぜこの処理が必要かを日本語で記載する。</p>
     */
    public void {{methodName1}}() {
        // 処理順に実装する。仕様外の機能は追加しない。
    }
}
```
