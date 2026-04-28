package com.propbot.propbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class GroupMeCallbackController {

    private static final Logger log = LoggerFactory.getLogger(GroupMeCallbackController.class);

    @PostMapping("/callback")
    public ResponseEntity<Void> onMessage(@RequestBody GroupMeMessage message) {
        if (message.system()) {
            log.info("[system] group_id={} text={}", message.groupId(), message.text());
        } else {
            log.info("[message] group_id={} sender={} text={}", message.groupId(), message.name(), message.text());
        }
        return ResponseEntity.ok().build();
    }
}
