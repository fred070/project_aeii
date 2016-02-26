package com.toyknight.aeii.network.server;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.ObjectSet;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Listener;
import com.esotericsoftware.kryonet.Server;
import com.esotericsoftware.minlog.Log;
import com.toyknight.aeii.AEIIException;
import com.toyknight.aeii.GameContext;
import com.toyknight.aeii.entity.GameCore;
import com.toyknight.aeii.entity.Map;
import com.toyknight.aeii.manager.GameEvent;
import com.toyknight.aeii.network.NetworkConstants;
import com.toyknight.aeii.network.entity.PlayerSnapshot;
import com.toyknight.aeii.network.entity.RoomSetting;
import com.toyknight.aeii.network.entity.RoomSnapshot;
import com.toyknight.aeii.utils.Encryptor;
import com.toyknight.aeii.utils.TileFactory;
import com.toyknight.aeii.utils.UnitFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author toyknight 10/27/2015.
 */
public class GameServer {

    private final String TAG = "Server";

    private String V_STRING;

    private Server server;

    private ExecutorService executor;

    private final Object PLAYER_LOCK = new Object();
    private ObjectMap<Integer, PlayerService> players;

    private final Object ROOM_LOCK = new Object();
    private ObjectMap<Long, Room> rooms;

    public PlayerService getPlayer(int id) {
        synchronized (PLAYER_LOCK) {
            return players.get(id);
        }
    }

    public void removePlayer(int id) {
        synchronized (PLAYER_LOCK) {
            players.remove(id);
        }
    }

    public boolean isRoomOpen(Room room) {
        return room != null && room.isOpen();
    }

    public boolean isRoomAvailable(Room room) {
        return room != null && !room.isGameOver() && room.getRemaining() > 0 && room.getHostID() != -1;
    }

    public Room getRoom(long room_number) {
        synchronized (ROOM_LOCK) {
            return rooms.get(room_number);
        }
    }

    public void addRoom(Room room) {
        synchronized (ROOM_LOCK) {
            rooms.put(room.getRoomNumber(), room);
        }
    }

    public void removeRoom(long room_number) {
        synchronized (ROOM_LOCK) {
            rooms.remove(room_number);
        }
        Log.info(TAG, String.format("Room [%d] is disposed", room_number));
    }

    public Array<RoomSnapshot> getRoomsSnapshots() {
        synchronized (ROOM_LOCK) {
            Array<RoomSnapshot> snapshots = new Array<RoomSnapshot>();
            ObjectMap.Values<Room> room_list = rooms.values();
            while (room_list.hasNext()) {
                snapshots.add(room_list.next().createSnapshot());
            }
            return snapshots;
        }
    }

    public RoomSetting createRoomSetting(Room room) {
        RoomSetting room_setting = new RoomSetting();
        room_setting.room_number = room.getRoomNumber();
        room_setting.started = !room.isOpen();
        room_setting.host = room.getHostID();
        room_setting.allocation = room.getAllocation();
        room_setting.start_gold = room.getStartGold();
        room_setting.max_population = room.getMaxPopulation();
        ObjectSet<Integer> players = room.getPlayers();
        room_setting.players = new Array<PlayerSnapshot>();
        room_setting.game = room.getGameCopy();
        for (int id : players) {
            PlayerService player = getPlayer(id);
            if (player != null) {
                PlayerSnapshot snapshot = player.createSnapshot();
                snapshot.is_host = room.getHostID() == id;
                room_setting.players.add(snapshot);
            }
        }
        return room_setting;
    }

    public void onPlayerConnect(Connection connection) {
        int id = connection.getID();
        PlayerService player = new PlayerService(connection);
        players.put(id, player);
    }

    public void onPlayerDisconnect(Connection connection) {
        PlayerService player = getPlayer(connection.getID());
        if (player != null) {
            String username = player.getUsername();
            String address = player.getAddress();
            onPlayerLeaveRoom(player.getID(), player.getRoomNumber());
            removePlayer(connection.getID());
            Log.info(TAG, String.format("%s@%s disconnected", username, address));
        }
    }

    public void onReceive(Connection connection, Object object) {
        if (object instanceof String) {
            ServiceTask task = new ServiceTask(getPlayer(connection.getID()), (String) object);
            executor.submit(task);
        }
    }

    public void onReceiveRequest(PlayerService player, JSONObject request) throws JSONException {
        switch (request.getInt("operation")) {
            case NetworkConstants.AUTHENTICATION:
                doAuthentication(player, request);
                break;
            case NetworkConstants.LIST_ROOMS:
                doRespondRoomList(player, request);
                break;
            case NetworkConstants.CREATE_ROOM:
                doRespondCreateRoom(player, request);
                break;
            case NetworkConstants.JOIN_ROOM:
                doRespondJoinRoom(player, request);
                break;
            case NetworkConstants.START_GAME:
                doRespondStartGame(player, request);
                break;
            case NetworkConstants.CREATE_ROOM_SAVED:
                doRespondCreateRoomSaved(player, request);
                break;
            default:
                //do nothing
        }
    }

    public void onReceiveNotification(PlayerService player, JSONObject notification) throws JSONException {
        switch (notification.getInt("operation")) {
            case NetworkConstants.PLAYER_LEAVING:
                onPlayerLeaveRoom(player.getID(), player.getRoomNumber());
                break;
            case NetworkConstants.UPDATE_ALLOCATION:
                onAllocationUpdate(player, notification);
                break;
            case NetworkConstants.GAME_EVENT:
                onSubmitGameEvent(player, notification);
                break;
            case NetworkConstants.MESSAGE:
                onSubmitMessage(player, notification);
                break;
            default:
                //do nothing
        }
    }

    public void doAuthentication(PlayerService player, JSONObject request) {
        try {
            JSONObject response = createResponse(request);

            String username = request.getString("username");
            String v_string = request.getString("v_string");

            if (V_STRING.equals(v_string)) {
                player.setAuthenticated(true);
                player.setUsername(username);
                response.put("approved", true);
                response.put("service_id", player.getID());
                Log.info(TAG, String.format("%s@%s authenticated.", player.getUsername(), player.getAddress()));
            } else {
                response.put("approved", false);
                Log.info(TAG, String.format("%s@%s authentication failed.", username, player.getAddress()));
            }
            player.getConnection().sendTCP(response.toString());
        } catch (JSONException ex) {
            String message = String.format(
                    "Bad authentication request from %s@%s", player.getUsername(), player.getAddress());
            Log.error(TAG, message, ex);
        }
    }

    public void doRespondRoomList(PlayerService player, JSONObject request) {
        try {
            if (player.isAuthenticated()) {
                JSONObject response = createResponse(request);
                JSONArray rooms = new JSONArray();
                for (RoomSnapshot snapshot : getRoomsSnapshots()) {
                    rooms.put(snapshot.toJson());
                }
                response.put("rooms", rooms);
                player.getConnection().sendTCP(response.toString());
            }
        } catch (JSONException ex) {
            String message = String.format(
                    "Bad room listing request from %s@%s", player.getUsername(), player.getAddress());
            Log.error(TAG, message, ex);
        }
    }

    public void doRespondCreateRoom(PlayerService player, JSONObject request) {
        try {
            if (player.isAuthenticated()) {
                JSONObject response = createResponse(request);
                Room room = new Room(System.currentTimeMillis(), player.getUsername() + "'s game");
                room.initialize(new Map(request.getJSONObject("map")));
                room.setMapName(request.getString("map_name"));
                room.setCapacity(request.getInt("capacity"));
                room.setStartGold(request.getInt("start_gold"));
                room.setMaxPopulation(request.getInt("max_population"));
                room.setHostPlayer(player.getID());
                room.addPlayer(player.getID());
                addRoom(room);
                player.setRoomNumber(room.getRoomNumber());

                RoomSetting room_setting = createRoomSetting(room);
                response.put("room_setting", room_setting.toJson());
                response.put("approved", true);

                Log.info(TAG, String.format(
                        "%s@%s creates room [%d]", player.getUsername(), player.getAddress(), room.getRoomNumber()));

                player.getConnection().sendTCP(response.toString());
            }
        } catch (JSONException ex) {
            String message = String.format(
                    "Bad room creating [new] request from %s@%s", player.getUsername(), player.getAddress());
            Log.error(TAG, message, ex);
        }
    }

    public void doRespondCreateRoomSaved(PlayerService player, JSONObject request) {
        try {
            if (player.isAuthenticated()) {
                JSONObject response = createResponse(request);
                GameCore game = new GameCore(request.getJSONObject("game"));
                Room room = new Room(System.currentTimeMillis(), player.getUsername() + "'s game", game);
                room.setMapName(request.getString("map_name"));
                room.setCapacity(request.getInt("capacity"));
                room.setHostPlayer(player.getID());
                room.addPlayer(player.getID());
                addRoom(room);
                player.setRoomNumber(room.getRoomNumber());

                RoomSetting room_setting = createRoomSetting(room);
                response.put("room_setting", room_setting.toJson());
                response.put("approved", true);

                Log.info(TAG, String.format(
                        "%s@%s creates room [%d]", player.getUsername(), player.getAddress(), room.getRoomNumber()));

                player.getConnection().sendTCP(response.toString());
            }
        } catch (JSONException ex) {
            String message = String.format(
                    "Bad room creating [save] request from %s@%s", player.getUsername(), player.getAddress());
            Log.error(TAG, message, ex);
        }
    }

    public void doRespondJoinRoom(PlayerService player, JSONObject request) {
        try {
            if (player.isAuthenticated()) {
                JSONObject response = createResponse(request);
                long room_number = request.getLong("room_number");
                Room room = getRoom(room_number);
                if (isRoomAvailable(room) && player.getRoomNumber() == -1) {
                    room.addPlayer(player.getID());
                    player.setRoomNumber(room_number);
                    RoomSetting room_setting = createRoomSetting(room);
                    response.put("room_setting", room_setting.toJson());
                    response.put("approved", true);
                    player.getConnection().sendTCP(response.toString());
                    notifyPlayerJoin(room, player.getID(), player.getUsername());
                } else {
                    response.put("approved", false);
                    player.getConnection().sendTCP(response.toString());
                }
            }
        } catch (JSONException ex) {
            String message = String.format(
                    "Bad room joining request from %s@%s", player.getUsername(), player.getAddress());
            Log.error(TAG, message, ex);
        }
    }

    public void doRespondStartGame(PlayerService player, JSONObject request) {
        try {
            if (player.isAuthenticated()) {
                JSONObject response = createResponse(request);
                Room room = getRoom(player.getRoomNumber());
                if (isRoomOpen(room) && room.isReady() && room.getHostID() == player.getID()) {
                    room.startGame();
                    response.put("approved", true);
                    notifyGameStart(room, player.getID());
                } else {
                    response.put("approved", false);
                }
                player.getConnection().sendTCP(response.toString());
            }
        } catch (JSONException ex) {
            String message = String.format(
                    "Bad game starting request from %s@%s", player.getUsername(), player.getAddress());
            Log.error(TAG, message, ex);
        }
    }

    public void onPlayerLeaveRoom(int leaver_id, long room_number) {
        PlayerService leaver = getPlayer(leaver_id);
        if (room_number >= 0 && leaver.getRoomNumber() == room_number) {
            Room room = getRoom(room_number);
            if (room != null) {
                room.removePlayer(leaver_id);
                leaver.setRoomNumber(-1);
                Log.info(TAG, String.format(
                        "%s@%s leaves room [%d]", leaver.getUsername(), leaver.getAddress(), room_number));
                if (room.getCapacity() == room.getRemaining()) {
                    removeRoom(room_number);
                } else {
                    notifyPlayerLeave(room, leaver.getID(), leaver.getUsername(), room.getHostID());
                    JSONArray types = new JSONArray();
                    JSONArray alliance = new JSONArray();
                    JSONArray allocation = new JSONArray();
                    for (int team = 0; team < 4; team++) {
                        types.put(room.getPlayerType(team));
                        alliance.put(room.getAlliance(team));
                        allocation.put(room.getAllocation(team));
                    }
                    notifyAllocationUpdate(room, -1, alliance, allocation, types);
                }
            }
        }
    }

    public void onAllocationUpdate(PlayerService updater, JSONObject notification) {
        try {
            if (updater.isAuthenticated()) {
                Room room = getRoom(updater.getRoomNumber());
                if (room != null && room.getHostID() == updater.getID()) {
                    JSONArray types = notification.getJSONArray("types");
                    JSONArray alliance = notification.getJSONArray("alliance");
                    JSONArray allocation = notification.getJSONArray("allocation");
                    for (int team = 0; team < 4; team++) {
                        room.setPlayerType(team, types.getInt(team));
                        room.setAlliance(team, alliance.getInt(team));
                        room.setAllocation(team, allocation.getInt(team));
                    }
                    notifyAllocationUpdate(room, updater.getID(), alliance, allocation, types);
                }
            }
        } catch (JSONException ex) {
            String message = String.format(
                    "Bad allocation updating notification from %s@%s", updater.getUsername(), updater.getAddress());
            Log.error(TAG, message, ex);
        }
    }

    public void onSubmitGameEvent(PlayerService submitter, JSONObject notification) {
        if (submitter.isAuthenticated()) {
//            GameEvent event = (GameEvent) notification.getParameter(0);
//            Room room = getRoom(submitter.getRoomNumber());
//            if (!room.isOpen()) {
//                room.submitGameEvent(event);
//                notifyGameEvent(room, submitter.getID(), event);
//            }
        }
    }

    public void onSubmitMessage(PlayerService submitter, JSONObject notification) {
        try {
            if (submitter.isAuthenticated()) {
                String message = notification.getString("message");
                Room room = getRoom(submitter.getRoomNumber());
                notifyMessage(room, submitter.getUsername(), message);
            }
        } catch (JSONException ex) {
            String message = String.format(
                    "Bad message notification from %s@%s", submitter.getUsername(), submitter.getAddress());
            Log.error(TAG, message, ex);
        }
    }

    public void notifyPlayerJoin(Room room, int joiner_id, String username) {
        for (int player_id : room.getPlayers()) {
            PlayerService player = getPlayer(player_id);
            if (player != null && joiner_id != player_id) {
                JSONObject notification = createNotification(NetworkConstants.PLAYER_JOINING);
                notification.put("player_id", joiner_id);
                notification.put("username", username);
                sendNotification(player, notification);
            }
        }
    }

    public void notifyPlayerLeave(Room room, int leaver_id, String username, int host_id) {
        for (int player_id : room.getPlayers()) {
            PlayerService player = getPlayer(player_id);
            if (player != null && leaver_id != player_id) {
                JSONObject notification = createNotification(NetworkConstants.PLAYER_LEAVING);
                notification.put("player_id", leaver_id);
                notification.put("username", username);
                notification.put("host_id", host_id);
                sendNotification(player, notification);
            }
        }
    }

    public void notifyAllocationUpdate(
            Room room, int updater_id, JSONArray alliance, JSONArray allocation, JSONArray types) {
        for (int player_id : room.getPlayers()) {
            PlayerService player = getPlayer(player_id);
            if (player != null && player_id != updater_id) {
                JSONObject notification = createNotification(NetworkConstants.UPDATE_ALLOCATION);
                notification.put("types", types);
                notification.put("alliance", alliance);
                notification.put("allocation", allocation);
                sendNotification(player, notification);
            }
        }
    }

    public void notifyGameStart(Room room, int starter_id) {
        for (int player_id : room.getPlayers()) {
            PlayerService player = getPlayer(player_id);
            if (player != null && player_id != starter_id) {
                JSONObject notification = createNotification(NetworkConstants.GAME_START);
                sendNotification(player, notification);
            }
        }
    }

    public void notifyGameEvent(Room room, int submitter_id, GameEvent event) {
        for (int player_id : room.getPlayers()) {
//            PlayerService player = getPlayer(player_id);
//            if (player != null && player_id != submitter_id) {
//                JSONObject notification = createNotification(NetworkConstants.GAME_EVENT);
//                notification.setParameters(event);
//                sendNotification(player, notification);
//            }
        }
    }

    public void notifyMessage(Room room, String username, String message) {
        for (int player_id : room.getPlayers()) {
            PlayerService player = getPlayer(player_id);
            if (player != null) {
                JSONObject notification = createNotification(NetworkConstants.MESSAGE);
                notification.put("username", username);
                notification.put("message", message);
                sendNotification(player, notification);
            }
        }
    }

    public void sendNotification(PlayerService player, JSONObject notification) {
        NotificationTask task = new NotificationTask(player.getConnection(), notification);
        executor.submit(task);
    }

    public String getVerificationString() {
        String V_STRING =
                TileFactory.getVerificationString() + UnitFactory.getVerificationString() + GameContext.VERSION;
        return new Encryptor().encryptString(V_STRING);
    }

    private void create() throws AEIIException {
        executor = Executors.newFixedThreadPool(64);
        server = new Server(65536, 65536);
        server.addListener(new Listener() {
            @Override
            public void connected(Connection connection) {
                onPlayerConnect(connection);
            }

            @Override
            public void disconnected(Connection connection) {
                onPlayerDisconnect(connection);
            }

            @Override
            public void received(Connection connection, Object object) {
                onReceive(connection, object);
            }
        });
        UnitFactory.loadUnitData();
        TileFactory.loadTileData();
        V_STRING = getVerificationString();
        players = new ObjectMap<Integer, PlayerService>();
        rooms = new ObjectMap<Long, Room>();
    }

    public void start() {
        try {
            create();
            server.start();
            server.bind(5438);
        } catch (IOException ex) {
            Log.error(TAG, "An error occurred while starting the server", ex);
        } catch (AEIIException ex) {
            Log.error(TAG, "An error occurred while creating the server", ex);
        }
    }

    private JSONObject createResponse(JSONObject request) {
        JSONObject response = new JSONObject();
        response.put("request_id", request.getLong("request_id"));
        response.put("type", NetworkConstants.RESPONSE);
        return response;
    }

    private JSONObject createNotification(int operation) {
        JSONObject notification = new JSONObject();
        notification.put("type", NetworkConstants.NOTIFICATION);
        notification.put("operation", operation);
        return notification;
    }

    private class ServiceTask implements Runnable {

        private final PlayerService player;
        private final String packet_content;

        public ServiceTask(PlayerService player, String packet_content) {
            this.player = player;
            this.packet_content = packet_content;
        }

        @Override
        public void run() {
            try {
                JSONObject packet = new JSONObject(packet_content);
                switch (packet.getInt("type")) {
                    case NetworkConstants.REQUEST:
                        onReceiveRequest(player, packet);
                        break;
                    case NetworkConstants.NOTIFICATION:
                        onReceiveNotification(player, packet);
                        break;
                }
            } catch (JSONException ex) {
                String message = String.format(
                        "An error occurred while processing packet from %s@%s",
                        player.getUsername(), player.getAddress());
                Log.error(TAG, message, ex);
            }
        }

    }

    private class NotificationTask implements Runnable {

        private final Connection connection;
        private final JSONObject notification;

        public NotificationTask(Connection connection, JSONObject notification) {
            this.connection = connection;
            this.notification = notification;
        }

        @Override
        public void run() {
            connection.sendTCP(notification.toString());
        }

    }

}
