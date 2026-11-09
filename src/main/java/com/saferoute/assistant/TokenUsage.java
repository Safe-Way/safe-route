package com.saferoute.assistant;

import java.util.List;

/**
 * 질문 한 번에 제미나이가 쓴 토큰 (비서 토큰 줄이기 R1). 제미나이 답의 usageMetadata 를 더한다.
 * 도구를 부르면 한 질문에 제미나이를 여러 번 부르므로(최대 5번) 그 합이다.
 * 질문 하나를 처리하는 동안만 쓰고 버린다 (여러 질문이 섞이지 않게 Turn 마다 새로 만듦). 말 원문은 담지 않는다.
 */
public final class TokenUsage {

    private int prompt;
    private int output;
    private int rounds;
    private String model;

    /** 제미나이 답 한 번의 usageMetadata 를 더한다. 출력에는 생각 토큰도 더한다 */
    synchronized void add(String modelName, int promptTokens, int outputTokens, int thoughtTokens) {
        model = modelName;
        prompt += Math.max(0, promptTokens);
        output += Math.max(0, outputTokens) + Math.max(0, thoughtTokens);
        rounds++;
    }

    /** 마지막으로 답한 모델 (바쁘면 다른 모델로 넘어가므로). 안 불렀으면 null */
    public synchronized String model() {
        return model;
    }

    public synchronized int rounds() {
        return rounds;
    }

    /** 앱·점검 스크립트에 줄 모양. 제미나이를 안 불렀으면 0 */
    public synchronized AssistantReply.Usage snapshot(List<String> toolsSent, boolean retried) {
        return rounds == 0 && !retried ? AssistantReply.Usage.NONE
                : new AssistantReply.Usage(prompt, output, rounds, toolsSent, retried);
    }
}
