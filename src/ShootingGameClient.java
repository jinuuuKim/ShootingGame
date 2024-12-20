import java.io.*;
import java.net.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.Timer;
import javax.swing.*;

public class ShootingGameClient extends JPanel implements ActionListener, KeyListener {
    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private final String clientId = "player_" + UUID.randomUUID();

    private long lastItemGenerationTime = 0;
    private static final long ITEM_GENERATION_INTERVAL = 5000; // 5초
    private Timer timer;
    private Image playerImage, backgroundImage, missileImage, hammerImage;
    private int playerX, playerY;
    private boolean[] keys;
    private List<Missile> missiles;
    private int directionX = 0;
    private int directionY = 0;
    private boolean isSpacePressed = false;
    private final Map<String, GameData> otherPlayers = new HashMap<>();
    private String playerRole;
    private boolean gameOver = false;
    private boolean gameOverPopupShown = false; // 팝업이 이미 표시되었는지 확인하는 플래그
    private boolean isWinner = false; // 승리 여부를 저장하는 변수
    private int lastDirectionX = 0;  // 마지막 X 방향
    private int lastDirectionY = -1; // 마지막 Y 방향 (기본적으로 위쪽)
    private int playerHP = 5;  // 자신의 HP
    private int speed = 5; // 기본 이동 속도
    private long speedBoostEndTime = 0; // 속도 증가 지속 시간
    private Image speedItemImage; // 속도 아이템 이미지
    private Image hpItemImage;  // HP 아이템 이미지
    private final List<GameData.Item> items = new ArrayList<>(); // 수신한 아이템 목록

    public ShootingGameClient() {
        try {
            socket = new Socket("localhost", 12345);
            System.out.println("서버에 연결 성공!");

            in = new ObjectInputStream(socket.getInputStream());
            out = new ObjectOutputStream(socket.getOutputStream());

            GameData initialData = (GameData) in.readObject();
            playerRole = initialData.getPlayerRole();

            if ("Player1".equals(playerRole)) {
                playerImage = new ImageIcon("images/kirby.png").getImage();
                playerX = 150;
                playerY = 500;
            } else {
                playerImage = new ImageIcon("images/dididi.png").getImage();
                playerX = 450;
                playerY = 100;
            }

            backgroundImage = new ImageIcon("images/background.png").getImage();
            missileImage = new ImageIcon("images/starBullet.png").getImage();
            hammerImage = new ImageIcon("images/hammer.png").getImage();
            speedItemImage = new ImageIcon("images/speed.png").getImage();
            hpItemImage = new ImageIcon("images/heart.png").getImage();

            keys = new boolean[256];
            missiles = new ArrayList<>();

            timer = new Timer(5, this);
            timer.start();

            addKeyListener(this);
            setFocusable(true);
            setPreferredSize(new Dimension(500, 600));

            new Thread(this::receiveData).start();
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("연결 실패: " + e.getMessage());
            JOptionPane.showMessageDialog(null, "서버에 연결할 수 없습니다.", "오류", JOptionPane.ERROR_MESSAGE);
            System.exit(0);
        }
    }

    public void paintComponent(Graphics g) {
        super.paintComponent(g);
        g.drawImage(backgroundImage, 0, 0, 500, 600, this);
        g.drawImage(playerImage, playerX, playerY, 50, 50, this);

        synchronized (items) {
            for (GameData.Item item : items) {
                Image itemImage = "speed".equals(item.getType()) ? speedItemImage : hpItemImage;
                g.drawImage(itemImage, item.getX(), item.getY(), 30, 30, this);
            }
        }

        for (Missile missile : missiles) {
            Image currentMissileImage = "Player1".equals(playerRole) ? missileImage : hammerImage;
            g.drawImage(currentMissileImage, missile.getX() - 20, missile.getY(), 20, 20, this);
        }

        synchronized (otherPlayers) {
            for (GameData data : otherPlayers.values()) {
                Rectangle player = data.getPlayer();
                if (player.x == 0 && player.y == 0) continue;
                Image otherPlayerImage = "Player1".equals(data.getPlayerRole())
                        ? new ImageIcon("images/kirby.png").getImage()
                        : new ImageIcon("images/dididi.png").getImage();
                g.drawImage(otherPlayerImage, player.x, player.y, 50, 50, this);

                for (Missile missile : data.getMissiles()) {
                    Image otherMissileImage = "Player1".equals(data.getPlayerRole()) ? missileImage : hammerImage;
                    g.drawImage(otherMissileImage, missile.getX() - 20, missile.getY(), 20, 20, this);
                }

                drawHpBar(g, player.x, player.y, data.getHp());
            }
        }

        drawHpBar(g, playerX, playerY, playerHP);

        if (gameOver && !gameOverPopupShown) {
            gameOverPopupShown = true;
            String message = isWinner ? "게임 오버! You Win!" : "게임 오버! You Lose!";
            JOptionPane.showMessageDialog(this, message);
            System.exit(0);
        }
    }

    private void drawHpBar(Graphics g, int playerX, int playerY, int hp) {
        int barWidth = 50;
        int barHeight = 5;

        g.setColor(Color.BLACK);
        g.fillRect(playerX, playerY - 10, barWidth, barHeight);

        int currentBarWidth = (int) (barWidth * ((double) hp / 5));
        g.setColor(Color.RED);
        g.fillRect(playerX, playerY - 10, currentBarWidth, barHeight);
    }

    public void actionPerformed(ActionEvent e) {
        if (System.currentTimeMillis() - lastItemGenerationTime > ITEM_GENERATION_INTERVAL) {
            lastItemGenerationTime = System.currentTimeMillis();
        }
        if (keys[KeyEvent.VK_LEFT]) {
            playerX -= speed;
            directionX = -1;
        } else if (keys[KeyEvent.VK_RIGHT]) {
            playerX += speed;
            directionX = 1;
        } else {
            directionX = 0;
        }

        if (keys[KeyEvent.VK_UP]) {
            playerY -= speed;
            directionY = -1;
        } else if (keys[KeyEvent.VK_DOWN]) {
            playerY += speed;
            directionY = 1;
        } else {
            directionY = 0;
        }

        if (directionX != 0 || directionY != 0) {
            lastDirectionX = directionX;
            lastDirectionY = directionY;
        }
        if (System.currentTimeMillis() > speedBoostEndTime) {
            speed = 5;
        }

        playerX = Math.max(0, Math.min(playerX, getWidth() - playerImage.getWidth(null)));
        playerY = Math.max(0, Math.min(playerY, getHeight() - playerImage.getHeight(null)));

        missiles.forEach(Missile::update);
        missiles.removeIf(missile -> missile.isOutOfBounds(getWidth(), getHeight()));

        synchronized (otherPlayers) {
            for (GameData data : otherPlayers.values()) {
                data.getMissiles().forEach(Missile::update);
                data.getMissiles().removeIf(missile -> missile.isOutOfBounds(getWidth(), getHeight()));
            }
        }

        detectCollisions();
        sendPlayerData();
        repaint();
    }




    private void sendItemRemovalToServer(GameData.Item item) {
        try {
            // 제거된 아이템의 ID를 `itemRemoved`로 설정
            GameData data = new GameData(
                    clientId,
                    null, // 플레이어 정보는 전달하지 않음
                    null, // 미사일 정보는 전달하지 않음
                    null, // 추가 아이템 없음
                    "room_1", // 현재 방 ID
                    playerRole,
                    playerHP
            );
            data.setItemRemoved(item.getId()); // 제거된 아이템 ID 설정
            out.writeObject(data); // 서버로 데이터 전송
            out.flush();
        } catch (IOException e) {
            System.err.println("아이템 제거 전송 오류: " + e.getMessage());
        }
    }



    private void detectCollisions() {
        // 1. 미사일 충돌 처리
        synchronized (otherPlayers) {
            for (Iterator<Missile> it = missiles.iterator(); it.hasNext(); ) {
                Missile missile = it.next();
                for (GameData data : otherPlayers.values()) {
                    Rectangle otherPlayer = data.getPlayer();
                    if (otherPlayer != null && missile.getX() >= otherPlayer.x && missile.getX() <= otherPlayer.x + otherPlayer.width &&
                            missile.getY() >= otherPlayer.y && missile.getY() <= otherPlayer.y + otherPlayer.height) {
                        it.remove(); // 미사일 제거
                        break; // 더 이상 충돌 체크 불필요
                    }
                }
            }
        }

        synchronized (otherPlayers) {
            for (GameData data : otherPlayers.values()) {
                List<Missile> otherMissiles = data.getMissiles();
                for (Iterator<Missile> it = otherMissiles.iterator(); it.hasNext(); ) {
                    Missile missile = it.next();
                    if (missile.getX() >= playerX && missile.getX() <= playerX + playerImage.getWidth(null) &&
                            missile.getY() >= playerY && missile.getY() <= playerY + playerImage.getHeight(null)) {
                        playerHP--; // 자신의 HP 감소
                        it.remove(); // 미사일 제거
                        sendPlayerData(); // 자신의 HP 정보 서버로 전송
                    }
                }
            }
        }

        // 2. 아이템 충돌 처리 (단순 제거 요청)
        synchronized (items) {
            Iterator<GameData.Item> itemIterator = items.iterator();
            while (itemIterator.hasNext()) {
                GameData.Item item = itemIterator.next();
                Rectangle itemBounds = new Rectangle(item.getX(), item.getY(), 30, 30);
                Rectangle playerBounds = new Rectangle(playerX, playerY, 50, 50);

                if (itemBounds.intersects(playerBounds)) {
                    if ("hp".equals(item.getType())) {
                        if (playerHP < 5) { // HP가 5 미만일 경우에만 증가
                            playerHP += 1; // HP 증가
                            sendPlayerData(); // HP 정보 서버로 전송
                        }
                        sendPlayerData(); // HP 정보 서버로 전송
                    } else if ("speed".equals(item.getType())) {
                        speed = 8; // 이동 속도 증가
                        speedBoostEndTime = System.currentTimeMillis() + 5000; // 5초 동안 지속
                    }
                    itemIterator.remove(); // 아이템 제거
                    sendItemRemovalToServer(item); // 서버에 아이템 제거 요청
                }
            }
        }



        // 3. 플레이어의 HP가 0이면 게임 종료
        if (playerHP <= 0) {
            gameOver = true;
        }
    }


    private void sendPlayerData() {
        try {
            GameData data = new GameData(
                    clientId,
                    new Rectangle(playerX, playerY, playerImage.getWidth(null), playerImage.getHeight(null)),
                    new ArrayList<>(missiles),
                    null, // 아이템 제거는 별도로 처리하므로 null
                    "room_1", // 고정된 방 ID
                    playerRole,
                    playerHP
            );
            out.writeObject(data); // 서버로 데이터 전송
            out.flush();
        } catch (IOException e) {
            System.err.println("데이터 전송 오류: " + e.getMessage());
        }
    }

    private void receiveData() {
        try {
            while (true) {
                GameData serverData = (GameData) in.readObject();

                // 다른 플레이어 정보 업데이트
                synchronized (otherPlayers) {
                    if (serverData.getPlayer() == null) {
                        otherPlayers.remove(serverData.getClientId());
                    } else {
                        otherPlayers.put(serverData.getClientId(), serverData);
                    }
                }

                // 아이템 정보 업데이트
                synchronized (items) {
                    if (serverData.getItems() != null) {
                        items.addAll(serverData.getItems());
                    }
                    if (serverData.getItemRemoved() != null) {
                        items.removeIf(item -> item.getId().equals(serverData.getItemRemoved()));
                    }
                }
                synchronized (otherPlayers) {
                    GameData otherPlayerData = otherPlayers.get(serverData.getClientId());
                    if (otherPlayerData != null) {
                        if (serverData.getClientId().equals(clientId)) {
                            // 자신의 데이터인 경우 HP 동기화
                            playerHP = serverData.getHp();
                        } else {
                            // 다른 플레이어 데이터 업데이트
                            otherPlayerData.setHp(serverData.getHp());
                        }

                    }
                }



                // 게임 종료 상태 처리
                if (serverData.isGameOver()) {
                    gameOver = true;
                    isWinner = serverData.isWinner();
                }

                repaint(); // 화면 갱신
            }
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("서버와의 연결이 끊겼습니다: " + e.getMessage());
        }
    }



    // 키 이벤트 처리
    public void keyPressed(KeyEvent e) {
        keys[e.getKeyCode()] = true;
        if (e.getKeyCode() == KeyEvent.VK_SPACE && !isSpacePressed) {
            isSpacePressed = true;
            shootMissile();
        }
    }

    public void keyReleased(KeyEvent e) {
        keys[e.getKeyCode()] = false;
        if (e.getKeyCode() == KeyEvent.VK_SPACE) {
            isSpacePressed = false;
        }
    }

    public void keyTyped(KeyEvent e) {}

    private void shootMissile() {
        int missileX = playerX + playerImage.getWidth(null) / 2 - missileImage.getWidth(null) / 2;
        int missileY = playerY + playerImage.getHeight(null) / 2 - missileImage.getHeight(null) / 2;

        // 마지막 저장된 방향으로 미사일 발사
        Missile newMissile = new Missile(missileX, missileY, lastDirectionX, lastDirectionY);
        missiles.add(newMissile);
    }

    public static void main(String[] args) {
        JFrame frame = new JFrame("Shooting Game - Client");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        ShootingGameClient gamePanel = new ShootingGameClient();
        frame.add(gamePanel);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        gamePanel.requestFocusInWindow();
    }
}