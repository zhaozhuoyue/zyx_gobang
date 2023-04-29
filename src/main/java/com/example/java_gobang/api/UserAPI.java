package com.example.java_gobang.api;

import com.example.java_gobang.model.User;
import com.example.java_gobang.model.UserMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletMapping;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

@RestController
public class UserAPI {

    @Resource
    private UserMapper userMapper;

    @PostMapping("/login")    //请求使用的是POST
    @ResponseBody    //将java对象转为json格式的数据
    public Object login(String username, String password, HttpServletRequest req){
        //关键操作：根据username去数据库中进行查询
        //如果能找到匹配的用户，并且密码也一致，就认为登陆成功
        User user = userMapper.selectByName(username);
        System.out.println("[login] user=" + username);
        if(user == null || !user.getPassword().equals(password)){
            //登陆失败
            System.out.println("登陆失败");
            return new User();//无效对象
        }
        HttpSession httpSession = req.getSession(true);
        //参数true的含义：会话存在直接返回，会话不存在就创建一个
        //参数false的含义：会话存在直接返回，会话不存在就返回空
        httpSession.setAttribute("user",user);
        return user;
    }

    @PostMapping("/register")
    @ResponseBody
    public Object register(String username,String password){
        try {
            User user = new User();
            user.setUsername(username);
            user.setPassword(password);
            userMapper.insert(user);
            return user;
        } catch (org.springframework.dao.DuplicateKeyException e){
            User user = new User();
            return user;
        }
    }

    @GetMapping("/userInfo")
    @ResponseBody
    public Object getUserInfo(HttpServletRequest req) {
        try {
            HttpSession httpSession = req.getSession(false);
            User user = (User) httpSession.getAttribute("user");
            //拿着这个user对象，去数据库中找，找到最新的数据
            User newUser = userMapper.selectByName(user.getUsername());
            return newUser;
        } catch (NullPointerException e){
            return new User();
        }
    }
}
