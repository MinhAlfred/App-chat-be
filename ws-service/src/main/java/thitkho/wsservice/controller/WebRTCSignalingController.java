package thitkho.wsservice.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import thitkho.wsservice.model.WebRTCSignal;

import java.security.Principal;

@Slf4j
@Controller
@RequiredArgsConstructor
public class WebRTCSignalingController {

    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/signal") // Client gửi tin nhắn vào đích: /app/signal
    public void processSignaling(@Payload WebRTCSignal signal, Principal principal) {
        if (principal == null) {
            log.warn("Unauthenticated signaling request");
            return;
        }

        // Ghi đè senderId bằng ID thật của user đang đăng nhập (tránh việc client giả mạo ID người khác)
        // Giả sử Principal.getName() trả về userId hoặc username
        String senderId = principal.getName();
        signal.setSenderId(senderId);

        log.info("Routing WebRTC signal [{}] from {} to {}", signal.getType(), senderId, signal.getTargetId());

        // Chuyển tiếp tin nhắn tới targetId
        // Đích đến thực tế ở phía client sẽ là: /user/queue/webrtc
        messagingTemplate.convertAndSendToUser(
                signal.getTargetId(),
                "/queue/webrtc",
                signal
        );
    }
}