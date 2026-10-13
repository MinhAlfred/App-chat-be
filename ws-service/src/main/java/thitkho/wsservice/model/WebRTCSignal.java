package thitkho.wsservice.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebRTCSignal {
    private String type;     // Các loại tín hiệu: "offer", "answer", "candidate", "leave"
    private String senderId; // ID của người gọi (được server ghi đè để bảo mật)
    private String targetId; // ID của người nhận

    // Sử dụng Object để chứa linh hoạt SDP (String) hoặc ICE Candidate (JSON Object)
    private Object data;
}
