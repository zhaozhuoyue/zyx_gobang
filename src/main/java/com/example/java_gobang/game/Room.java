package com.example.java_gobang.game;


import com.example.java_gobang.JavaGobangApplication;
import com.example.java_gobang.model.User;
import com.example.java_gobang.model.UserMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.UUID;

//这个类就表示一个游戏房间
public class Room {
    //使用字符串类型来表示，方便生成唯一值
    private String roomId;
    private User user1;
    private User user2;

    //先手方的的玩家 id
    private int whiteUser;

    private static final int MAX_ROW = 15;
    private static final int MAX_COL = 15;
    //这个二维数组用来表示棋盘
    //约定：
    // 1) 使用0表示当前位置 未落子，初始化好的int二维数组就相当于是全0
    // 2) 使用1表示 user1 的落子位置
    // 3) 使用2表示 user2 的落子位置
    private int [][] board = new int[MAX_ROW][MAX_COL];

    //创建ObjectMapper用来转换 JSON
    private ObjectMapper objectMapper = new ObjectMapper();

    //引入OnlineUserManager
    //@Autowired
    private OnlineUserManager onlineUserManager;

    //引入RoomManager，用来房间销毁
    //@Autowired
    private RoomManager roomManager;

    private UserMapper userMapper;

    //通过这个方法来处理一次落子操作
    //要做的事情：
    public void putChess(String reqJson) throws IOException {
        //1.记录当前落子的位置
        GameRequest request = objectMapper.readValue(reqJson,GameRequest.class);
        GameResponse response = new GameResponse();
        //当前这个子是玩家1落得还是玩家2落的。根据这个玩家1 和玩家2 来决定往数组中写1还是2
        int chess = request.getUserId() == user1.getUserId() ? 1 : 2;
        int row = request.getRow();
        int col = request.getCol();
        if (board[row][col] != 0) {
            //在客户端已经针对重复落子进行过判定，此处为了程序更加稳健，在服务器再判定一次
            System.out.println("当前位置(" + row + "," + col + ")已经有子啦！");
            return;
        }
        board[row][col] = chess;
        //2.打印出当前的棋盘信息，方便来观察局势，也方便后面验证胜负关系的判定
        printBoard();
        //3.进行胜负判定
        int winner = checkWinner(row,col,chess);
        //4.给房间中的所有客户端返回响应
        response.setMessage("putChess");
        response.setUserId(request.getUserId());
        response.setRow(row);
        response.setCol(col);
        response.setWinner(winner);

        //要想给用户发送 websocket 数据，就需要获取到这个用户 WebSocketSession
        WebSocketSession session1 = onlineUserManager.getFromGameRoom(user1.getUserId());
        WebSocketSession session2 = onlineUserManager.getFromGameRoom(user2.getUserId());
        //万一当前查到的对话为空（玩家下线）特殊处理一下
        if(session1 == null) {
            //玩家1 已经下线了。直接认为玩家2获胜
            response.setWinner(user2.getUserId());
            System.out.println("玩家1 掉线！");
        }
        if(session2 == null) {
            //玩家2 已经下线了。直接认为玩家1获胜
            response.setWinner(user1.getUserId());
            System.out.println("玩家2 掉线！");
        }
        //把响应构造成JSON字符串，通过session进行传输
        String respJson = objectMapper.writeValueAsString(response);
        if(session1 != null) {
            session1.sendMessage(new TextMessage(respJson));
        }
        if(session2 != null) {
            session2.sendMessage(new TextMessage(respJson));
        }

        //5.如果玩家1和万家胜负已分，那么就可以销毁房间
        //把房间从房间管理器中移除
        if(response.getWinner() != 0) {
            //胜负已分
            System.out.println("游戏结束！房间即将销毁！roomId=" + roomId + "获胜方" + response.getWinner());
            //更新获胜方和是拜访的信息
            int winUserId = response.getWinner();
            int loseUserId = response.getWinner() == user1.getUserId() ? user2.getUserId() : user1.getUserId();
            userMapper.userWin(winUserId);
            userMapper.userLose(loseUserId);
            //销毁房间
            roomManager.remove(roomId,user1.getUserId(),user2.getUserId());
        }
    }

    private void printBoard() {
        //打印出qipan
        System.out.println("[打印棋盘信息]" + roomId);
        System.out.println("====================================================================");
        for (int r = 0; r < MAX_ROW; r++) {
            for (int c = 0; c < MAX_COL; c++) {
                //针对一行之内的若干列，不要打印换行
                System.out.print(board[r][c] + " ");
            }
            //每次遍历完一行之后，再打印换行
            System.out.println();
        }
        System.out.println("====================================================================");

    }

    //使用这个方法进行判定
    //如果玩家1获胜，就返回玩家1的id
    //如果玩家2获胜，就返回玩家2的id
    //胜负未分 返回0
    private int checkWinner(int row, int col, int chess) {
        //1.检查所有的行
        //  先遍历这五种情况
        for (int c = col - 4; c <= col ; c++) {
            //针对其中的一种情况，来判定这五个子是不是连在一起了
            //不光是这五个子得连着，而且要跟玩家落的子是一样的 才算获胜
            try {
                if(board[row][c] == chess
                        && board[row][c+1] == chess
                        && board[row][c+2] == chess
                        && board[row][c+3] == chess
                        && board[row][c+4] == chess) {
                    //构成了五子连珠！胜负已分
                    return chess == 1 ? user1.getUserId() : user2.getUserId();
                }
            }catch (ArrayIndexOutOfBoundsException e) {
                //如果出现数组下标越界得情况，可以直接忽略这个异常
                continue;
            }
        }

        //2.检查所有列
        for(int r = row - 4;r <= row;r++) {
            try {
                if(board[r][col] == chess
                    && board[r+1][col] == chess
                    && board[r+2][col] == chess
                    && board[r+3][col] == chess
                    && board[r+4][col] == chess) {
                    return chess == 1 ? user1.getUserId() : user2.getUserId();
                }
            }catch (ArrayIndexOutOfBoundsException e){
                continue;
            }
        }


        //3.检查左对角线
        for (int r = row - 4,c = col - 4; r <=  row && c <= col;r++,c++){
            try {
                if (board[r][c] == chess
                    && board[r + 1][c + 1] == chess
                    && board[r + 2][c + 2] == chess
                    && board[r + 3][c + 3] == chess
                    && board[r + 4][c + 4] == chess) {
                    return chess  == 1 ? user1.getUserId() : user2.getUserId();
                }
            }catch (ArrayIndexOutOfBoundsException e) {
                continue;
            }
        }

        //4.检查右对角线
        for (int r = row - 4,c = col + 4; r <=  row && c >= col;r++,c--){
            try {
                if (board[r][c] == chess
                        && board[r + 1][c - 1] == chess
                        && board[r + 2][c - 2] == chess
                        && board[r + 3][c - 3] == chess
                        && board[r + 4][c - 4] == chess) {
                    return chess  == 1 ? user1.getUserId() : user2.getUserId();
                }
            }catch (ArrayIndexOutOfBoundsException e) {
                continue;
            }
        }

        //胜负未分，直接返回0
        return 0;
    }

    public int getWhiteUser() {
        return whiteUser;
    }

    public void setWhiteUser(int whiteUser) {
        this.whiteUser = whiteUser;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public User getUser1() {
        return user1;
    }

    public void setUser1(User user1) {
        this.user1 = user1;
    }

    public User getUser2() {
        return user2;
    }

    public void setUser2(User user2) {
        this.user2 = user2;
    }

    public Room() {
        //构造Room的时候生成唯一的字符串表示房间id
        //使用UUID来作为房间id
        roomId = UUID.randomUUID().toString();
        //通过入口类中记录的context来手动获取到前面的roommanager和onlineUserManager
        onlineUserManager = JavaGobangApplication.context.getBean(OnlineUserManager.class);
        roomManager = JavaGobangApplication.context.getBean(RoomManager.class);
        userMapper = JavaGobangApplication.context.getBean(UserMapper.class);
    }

    public static void main(String[] args) {
        Room room = new Room();
        System.out.println(room.roomId);
    }
}
