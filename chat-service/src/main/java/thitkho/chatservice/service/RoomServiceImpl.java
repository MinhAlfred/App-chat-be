package thitkho.chatservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import thitkho.chatservice.annotation.RequireRoomMember;
import thitkho.chatservice.client.UserClient;
import thitkho.chatservice.dto.mapper.RoomMapper;
import thitkho.chatservice.dto.request.CreateRoomRequest;
import thitkho.chatservice.dto.request.UpdateRoomRequest;
import thitkho.chatservice.dto.response.RoomResponse;
import thitkho.chatservice.exception.RoomErrorCode;
import thitkho.chatservice.model.Room;
import thitkho.chatservice.model.RoomMember;
import thitkho.chatservice.model.enums.MemberRole;
import thitkho.chatservice.model.enums.RoomType;
import thitkho.chatservice.repository.RoomMemberRepository;
import thitkho.chatservice.repository.RoomRepository;
import thitkho.exception.AppException;
import thitkho.payload.CursorPage;
import thitkho.dto.response.UserInfoChatResponse;
import thitkho.chatservice.producer.ChatEventProducer;
import thitkho.chatservice.util.RoomUtils;
import thitkho.constant.KafkaTopics;
import thitkho.payload.event.ChatEvent;
import thitkho.payload.event.room.RoomCreatedPayload;
import thitkho.payload.event.room.RoomDeletedPayload;
import thitkho.payload.event.room.RoomEventType;
import thitkho.payload.event.room.RoomReadPayload;
import thitkho.payload.event.room.RoomUpdatedPayload;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
@Slf4j
public class RoomServiceImpl implements RoomService {
    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final UserClient userClient;
    private final ChatEventProducer chatEventProducer;

    @Override
    @Transactional
    public RoomResponse getOrCreatePrivateRoom(String userId, String targetUserId) {
        log.info("Request getOrCreatePrivateRoom - userId: {}, targetUserId: {}", userId, targetUserId);
        UserInfoChatResponse targetUserInfo = userClient.getUserById(targetUserId);

        String directHash = RoomUtils.generateDirectHash(userId, targetUserId);
        log.debug("Generated directHash: {}", directHash);

        return roomRepository.findByDirectHash(directHash)
                .map(room -> {
                    log.info("Private room already exists - roomId: {}", room.getId());
                    int unread = roomMemberRepository.findByRoomIdAndUserId(room.getId(), userId)
                            .map(RoomMember::getUnreadCount)
                            .orElse(0);
                    return RoomMapper.toDirectRoomResponse(room, targetUserInfo, unread, false);
                })
                .orElseGet(() -> {
                    log.info("Private room not found, creating new room between {} and {}", userId, targetUserId);
                    Room room = createDirectRoom(targetUserInfo.displayName(), userId, directHash);
                    saveMembers(room.getId(), List.of(userId, targetUserId), false);

                    publishRoomEvent(
                            room.getId(),
                            RoomEventType.ROOM_CREATED,
                            new RoomCreatedPayload(
                                    room.getId(),
                                    targetUserInfo.displayName(),
                                    targetUserInfo.avatar(),
                                    null,
                                    room.getType().toString(),
                                    room.getCreatedBy(),
                                    room.getLastMessageContent(),
                                    room.getMemberCount(),
                                    room.getLastMessageAt(),
                                    room.getCreatedAt(),
                                    List.of(userId, targetUserId)
                            )
                    );
                    log.info("Successfully created private room - roomId: {}", room.getId());
                    return RoomMapper.toDirectRoomResponse(room, targetUserInfo, 0, true);
                });
    }

    @Override
    @Transactional
    public RoomResponse createGroupChatRoom(String userId, CreateRoomRequest request) {
        log.info("Request createGroupChatRoom - userId: {}, request: {}", userId, request);
        UserInfoChatResponse userInfo = userClient.getUserById(userId);

        int count = request.memberIds().size() + 1;
        Room room = createBaseRoom(request.name(), RoomType.GROUP, userId, count, userInfo.displayName());

        log.debug("Saving members for group room - roomId: {}", room.getId());
        saveMembers(room.getId(), List.of(userId), true); // owner
        saveMembers(room.getId(), request.memberIds(), false); // members

        List<String> allMemberIds = new ArrayList<>(request.memberIds());
        allMemberIds.add(userId);

        publishRoomEvent(
                room.getId(),
                RoomEventType.ROOM_CREATED,
                new RoomCreatedPayload(
                        room.getId(),
                        request.name(),
                        request.avatar(),
                        request.description(),
                        room.getType().toString(),
                        room.getCreatedBy(),
                        room.getLastMessageContent(),
                        room.getMemberCount(),
                        room.getLastMessageAt(),
                        room.getCreatedAt(),
                        allMemberIds
                )
        );
        log.info("Successfully created group chat room - roomId: {}, totalMembers: {}", room.getId(), count);
        return RoomMapper.toGroupRoomResponse(room, userInfo, 0);
    }

    @Override
    @RequireRoomMember
    public RoomResponse getRoom(String userId, String roomId) {
        log.info("Request getRoom - userId: {}, roomId: {}", userId, roomId);
        Room room = findRoomById(roomId);

        RoomMember me = roomMemberRepository.findByRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> {
                    log.warn("User {} is not a member of room {}", userId, roomId);
                    return new AppException(RoomErrorCode.USER_NOT_IN_ROOM);
                });

        if (room.getType() == RoomType.DIRECT) {
            log.debug("Room is DIRECT type, fetching target user info");
            List<RoomMember> members = roomMemberRepository.findByRoomId(roomId);
            String targetUserId = members.stream()
                    .map(RoomMember::getUserId)
                    .filter(id -> !id.equals(userId))
                    .findFirst()
                    .orElse(userId);
            UserInfoChatResponse targetInfo = userClient.getUserById(targetUserId);
            return RoomMapper.toDirectRoomResponse(room, targetInfo, me.getUnreadCount(), false);
        }

        log.debug("Room is GROUP type, fetching last message sender info");
        UserInfoChatResponse user = userClient.getUserById(room.getLastMessageSenderId());
        if (user == null) {
            log.error("Room last message sender not found - senderId: {}", room.getLastMessageSenderId());
            throw new AppException(RoomErrorCode.ROOM_NOT_FOUND, "Room last message sender not found");
        }

        return RoomMapper.toGroupRoomResponse(room, user, me.getUnreadCount());
    }

    @Override
    public CursorPage<RoomResponse> getMyRooms(String userId, String cursor, int limit) {
        log.info("Request getMyRooms - userId: {}, cursor: {}, limit: {}", userId, cursor, limit);
        LocalDateTime cursorTime = cursor != null ? LocalDateTime.parse(cursor) : LocalDateTime.now();

        List<Room> rooms = roomRepository.findRoomsByUserIdWithCursor(userId, cursorTime, limit);
        log.info("Fetched {} rooms for user {}", rooms.size(), userId);

        if (rooms.isEmpty()) {
            return CursorPage.of(List.of(), limit, null);
        }

        Set<String> allNeededUserIds = new HashSet<>();
        List<String> roomTargetId = rooms.stream().map(Room::getId).toList();
        log.debug("roomTargetIds list: {}", roomTargetId);

        Map<String, List<RoomMember>> roomIdToMember = roomMemberRepository.findAllByRoomIdIn(roomTargetId).stream()
                .collect(Collectors.groupingBy(RoomMember::getRoomId));

        Map<String, String> roomIdToTargetUserId = new HashMap<>();
        rooms.forEach(room -> {
            if (room.getType() == RoomType.DIRECT) {
                List<RoomMember> members = roomIdToMember.getOrDefault(room.getId(), List.of());
                String targetUserId = members.stream()
                        .map(RoomMember::getUserId)
                        .filter(id -> !id.equals(userId))
                        .findFirst()
                        .orElse(userId);
                roomIdToTargetUserId.put(room.getId(), targetUserId);
                allNeededUserIds.add(targetUserId);
            }
            if (room.getLastMessageSenderId() != null) {
                allNeededUserIds.add(room.getLastMessageSenderId());
            }
        });

        log.debug("Fetching user infos for IDs: {}", allNeededUserIds);
        Map<String, UserInfoChatResponse> userIdToInfo = userClient.getUsersByIds(new ArrayList<>(allNeededUserIds));

        // 3. Map vào Response
        List<RoomResponse> responses = rooms.stream().map(room -> {
            RoomMember me = roomIdToMember.get(room.getId()).stream()
                    .filter(m -> m.getUserId().equals(userId))
                    .findFirst()
                    .orElseThrow(() -> {
                        log.error("Data inconsistency: User {} not found in RoomMember list for room {}", userId, room.getId());
                        return new AppException(RoomErrorCode.USER_NOT_IN_ROOM);
                    });

            if (room.getType() == RoomType.DIRECT) {
                String target = roomIdToTargetUserId.get(room.getId());
                UserInfoChatResponse targetInfo = userIdToInfo.get(target);
                return RoomMapper.toDirectRoomResponse(room, targetInfo, me.getUnreadCount(), false);
            }
            UserInfoChatResponse senderInfo = userIdToInfo.get(room.getLastMessageSenderId());
            return RoomMapper.toGroupRoomResponse(room, senderInfo, me.getUnreadCount());
        }).toList();

        return CursorPage.of(
                responses,
                limit,
                r -> r.lastMessageAt().toString() // extract cursor từ item cuối
        );
    }

    @Override
    @Transactional
    public RoomResponse updateRoom(String userId, String roomId, UpdateRoomRequest request) {
        log.info("Request updateRoom - userId: {}, roomId: {}, request: {}", userId, roomId, request);
        Room room = findRoomById(roomId);

        if (room.getType() != RoomType.DIRECT) {
            validateAdminAccess(userId, roomId);
        }

        boolean isUpdated = false;
        if (request.name() != null) {
            room.setName(request.name());
            isUpdated = true;
        }
        if (request.avatar() != null && room.getType() != RoomType.DIRECT) {
            room.setAvatar(request.avatar());
            isUpdated = true;
        }
        if (request.description() != null && room.getType() != RoomType.DIRECT) {
            room.setDescription(request.description());
            isUpdated = true;
        }

        if (isUpdated) {
            roomRepository.save(room);
            log.debug("Room info updated in DB - roomId: {}", roomId);

            publishRoomEvent(
                    roomId,
                    RoomEventType.ROOM_UPDATED,
                    new RoomUpdatedPayload(
                            roomId,
                            room.getName(),
                            room.getAvatar(),
                            room.getDescription()
                    )
            );
        }

        UserInfoChatResponse senderInfo = null;
        if (room.getLastMessageSenderId() != null) {
            senderInfo = userClient.getUserById(room.getLastMessageSenderId());
        }

        RoomMember me = roomMemberRepository.findByRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new AppException(RoomErrorCode.USER_NOT_IN_ROOM));

        return RoomMapper.toGroupRoomResponse(room, senderInfo, me.getUnreadCount());
    }

    @Override
    @Transactional
    public void deleteRoom(String userId, String roomId) {
        log.info("Request deleteRoom - userId: {}, roomId: {}", userId, roomId);
        Room room = findRoomById(roomId);

        if (room.getType() != RoomType.DIRECT) {
            validateOwnerAccess(userId, roomId);
        }

        List<String> memberIds = roomMemberRepository.findByRoomId(roomId).stream()
                .map(RoomMember::getUserId)
                .toList();

        room.setActive(false);
        roomRepository.save(room);
        log.info("Room marked as inactive - roomId: {}", roomId);

        publishRoomEvent(
                roomId,
                RoomEventType.ROOM_DELETED,
                new RoomDeletedPayload(roomId, memberIds)
        );
    }

    @Override
    public void markAsRead(String userId, String roomId) {
        log.debug("Request markAsRead - userId: {}, roomId: {}", userId, roomId);
        RoomMember member = roomMemberRepository.findByRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new AppException(RoomErrorCode.USER_NOT_IN_ROOM));

        member.setUnreadCount(0);
        member.setLastReadAt(LocalDateTime.now());
        roomMemberRepository.save(member);

        publishRoomEvent(
                roomId,
                RoomEventType.ROOM_READ,
                new RoomReadPayload(
                        roomId,
                        userId
                )
        );
    }

    @Override
    public RoomResponse joinByInviteCode(String userId, String inviteCode) {
        log.info("Request joinByInviteCode - userId: {}, inviteCode: {}", userId, inviteCode);
        return null;
    }

    @Override
    public RoomResponse resetInviteCode(String userId, String roomId) {
        log.info("Request resetInviteCode - userId: {}, roomId: {}", userId, roomId);
        return null;
    }

    // --- Helper Methods ---
    private Room createDirectRoom(String name, String creatorId, String directHash) {
        log.debug("Creating direct room entity - creatorId: {}", creatorId);
        Room room = Room.builder()
                .name(name)
                .type(RoomType.DIRECT)
                .createdBy(creatorId)
                .isActive(true)
                .memberCount(2)
                .directHash(directHash)
                .build();
        return roomRepository.save(room);
    }

    private Room createBaseRoom(String name, RoomType type, String creatorId, int memberCount, String creatorName) {
        log.debug("Creating base room entity - name: {}, type: {}, creatorId: {}", name, type, creatorId);
        String content = "Room created by: " + creatorName;
        LocalDateTime now = LocalDateTime.now();
        Room room = Room.builder()
                .name(name)
                .type(type)
                .createdBy(creatorId)
                .isActive(true)
                .lastMessageSenderId(creatorId)
                .lastMessageContent(content)
                .lastMessageAt(now)
                .memberCount(memberCount)
                .build();
        return roomRepository.save(room);
    }

    private void saveMembers(String roomId, List<String> userIds, boolean isOwner) {
        log.debug("Saving {} members for room {} (isOwner: {})", userIds.size(), roomId, isOwner);
        MemberRole role = isOwner ? MemberRole.OWNER : MemberRole.MEMBER;
        userIds.forEach(userId -> {
            LocalDateTime now = LocalDateTime.now();
            RoomMember member = RoomMember.builder()
                    .roomId(roomId)
                    .userId(userId)
                    .role(role)
                    .joinedAt(now)
                    .lastReadAt(now)
                    .unreadCount(0)
                    .build();
            roomMemberRepository.save(member);
        });
    }

    private Room findRoomById(String roomId) {
        return roomRepository.findById(roomId)
                .orElseThrow(() -> {
                    log.error("Room not found - roomId: {}", roomId);
                    return new AppException(RoomErrorCode.ROOM_NOT_FOUND);
                });
    }

    private void validateAdminAccess(String userId, String roomId) {
        log.debug("Validating admin access - userId: {}, roomId: {}", userId, roomId);
        RoomMember member = roomMemberRepository.findByRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new AppException(RoomErrorCode.NOT_A_MEMBER));
        if (member.getRole() != MemberRole.OWNER && member.getRole() != MemberRole.ADMIN) {
            log.warn("Access denied: User {} is not Admin/Owner of room {}", userId, roomId);
            throw new AppException(RoomErrorCode.USER_NOT_PERMISSION);
        }
    }

    private void validateOwnerAccess(String userId, String roomId) {
        log.debug("Validating owner access - userId: {}, roomId: {}", userId, roomId);
        RoomMember member = roomMemberRepository.findByRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new AppException(RoomErrorCode.NOT_A_MEMBER));

        if (member.getRole() != MemberRole.OWNER) {
            log.warn("Access denied: User {} is not Owner of room {}", userId, roomId);
            throw new AppException(RoomErrorCode.USER_NOT_PERMISSION);
        }
    }

    private void publishRoomEvent(String roomId, RoomEventType eventType, Object payload) {
        String topic = switch (eventType) {
            case ROOM_CREATED, ROOM_DELETED, ROOM_UPDATED -> KafkaTopics.ROOM_METADATA;
            default -> KafkaTopics.ROOM_EVENTS;
        };
        log.debug("Publishing event to Kafka - topic: {}, eventType: {}, roomId: {}", topic, eventType, roomId);
        chatEventProducer.publish(topic, roomId, new ChatEvent<>(eventType.name(), roomId, payload));
    }
}