package com.bryan.features.chat.web;

import io.smallrye.mutiny.Multi;
import java.util.List;

public interface ChatTokenSource {
    Multi<String> stream(String question,List<ChatCommand.HistoryMessage> history);
}
