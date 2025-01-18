package com.may;

import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.db.Db;
import cn.hutool.db.Entity;
import cn.hutool.db.Session;
import cn.hutool.db.ds.DSFactory;
import cn.hutool.extra.emoji.EmojiUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import com.may.utils.MailUtils;
import org.apache.commons.mail.util.MimeMessageUtils;

import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.sql.DataSource;
import java.io.*;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

public class ParseMail {

    static final Log log = LogFactory.get();
    static DataSource DS = DSFactory.get();

    static volatile AtomicInteger count = new AtomicInteger(0);
    static StringBuffer unInsert = new StringBuffer();


    public static void main(String[] args) {

        List<Entity> dataList = new ArrayList<>();
        try {
            dataList = Db.use(DS).query("SELECT mid mailld,path mail_path FROM uninsert");

        } catch (Exception e) {
            e.printStackTrace();
        }

        final int dataSize = dataList.size();
        CountDownLatch latch = ThreadUtil.newCountDownLatch(dataSize);
        long begin = System.currentTimeMillis();

        //创建线程池
        ExecutorService pool = ThreadUtil.newExecutor(30);

        // 保存
        Session session = Session.create(DS);

        for (Entity data : dataList){

            pool.execute(() -> {
                String mailld = data.getStr("mailld");
                String mailPath = data.getStr("mail_path");

                String[] split = mailPath.split("[/]+");
                int len = split.length;
                // 文件名带上一层目录，才是唯一的
                String mailName =  split[len - 2] + "/" + split[len - 1].replaceAll(".eml","");
                MailUtils mailUtils = MailUtils.create();

                try {
                    MimeMessage mimeMessage = MimeMessageUtils.createMimeMessage(null, new File(mailPath));

                    // 邮件内容明细表
                    Entity mailDetail = Entity.create("zzz_mail_detail");
                    // 存储mail_deail 表的备注字段
                    StringBuffer remarkBuffer = new StringBuffer();

                    try {
                        InternetAddress send = mailUtils.getSendAddress(mimeMessage);
                        // 没有发件人
                        if (send != null){
                            // 发件人姓名
                            String sender = send.getPersonal();
                            if (EmojiUtil.containsEmoji(sender)){
                                sender = EmojiUtil.removeAllEmojis(sender);
                            }
                            mailDetail.set("mail_sender_name", sender);
                            // 发件人地址
                            mailDetail.set("mail_sender", send.getAddress());
                        }
                    } catch (Exception e) {
                        log.error("发件人无法解析！");
                        remarkBuffer.append("发件人无法解析;");
                    }

                    try {
                        String subject = mailUtils.getSubject(mimeMessage);
                        // 没有主题
                        if (subject != null){
                            // 邮件主题
                            mailDetail.set("mail_title", subject);
                        }
                    } catch (Exception e) {
                        log.error("邮件主题无法解析！");
                        remarkBuffer.append("邮件主题无法解析;");
                    }

                    try {
                        // 邮件内容
                        String content = mailUtils.getContent(mimeMessage);
                        if( content != null) {
                            mailDetail.set("mail_text", content);
                        }
                    } catch (Exception e) {
                        log.error("邮件内容无法解析！");
                        remarkBuffer.append("邮件内容无法解析;");
                    }

                    try {
                        String sentDate = mailUtils.getSentDate(mimeMessage);
                        if (sentDate != null){
                            // 发送日期
                            mailDetail.set("mail_time", sentDate);
                        }
                    } catch (Exception e) {
                        log.error("发送日期无法解析！");
                        remarkBuffer.append("发送日期无法解析;");
                    }

                    // 邮件 html 格式
//                        mailDetail.set("mail_html",null);// todo 待实现

                    // 来源邮件正文 id
                    mailDetail.set("from_mailld", mailld);

                    // 收件人表
                    ArrayList<Entity> receiveEntityList = null;
                    try {
                        List<InternetAddress> receives = mailUtils.getReceiveAddress(mimeMessage, null);// type 为空则获取所有收件人，含抄送、密送人
                        receiveEntityList = null;
                        if (receives != null){
                            receiveEntityList = new ArrayList<>();
                            // 邮件收件人表
                            for (InternetAddress receive : receives){
                                Entity mailAddressee = Entity.create("zzz_mail_addressee");
                                String receiver = receive.getPersonal();
                                if (EmojiUtil.containsEmoji(receiver)){
                                    receiver = EmojiUtil.removeAllEmojis(receiver);
                                }
                                mailAddressee.set("mail_addressee", receiver);
                                mailAddressee.set("mail_addressee_add", receive.getAddress());
                                mailAddressee.set("from_mailld", mailld);
                                receiveEntityList.add(mailAddressee);
                            }
                        }
                    } catch (Exception e) {
                        log.error("收件人无法解析！");
                        remarkBuffer.append("收件人无法解析;");
                    }
                    // 附件表
                    ArrayList<Entity> attacmentEntityList = null;
                    try {
                        // 有的邮件解析附件会抛异常
                        if (mailUtils.isContainAttachment(mimeMessage)) {
                            attacmentEntityList = new ArrayList<>();
                            // 存放附件路径
                            List<Map<String,String>> attachmentList = new ArrayList<>();
                            // 附件路径示例： /mnt/db/mail/attachment/administrator_mljr.com/ZA0729fv9UO5UtTh6TRSZ_vJe0Bg8n/附件xx
                            // 不下载，仅保存路径
                            mailUtils.saveAttachment(mimeMessage, "/mnt/db/mail/attachment/" + mailName + "/",attachmentList);
                            // 邮件附件表
                            for (Map map : attachmentList){
                                Entity mailAttachment = Entity.create("zzz_mail_attachment");
                                mailAttachment.set("mail_attachment",map.get("fileName"));
                                mailAttachment.set("mail_at_path",map.get("filePath"));
                                mailAttachment.set("from_mailld",mailld);
                                attacmentEntityList.add(mailAttachment);
                            }
                        }
                    } catch (Exception e) {
                        log.error("附件无法解析！");
                        remarkBuffer.append("附件无法解析;");
                    }
                    // 备注
                    if (remarkBuffer.length() > 0){
                        mailDetail.set("remark",remarkBuffer.toString());
                    }

                    try {
                        session.beginTransaction();
                        session.insert(mailDetail);
                        if (receiveEntityList != null){
                            for (Entity entity : receiveEntityList){
                                session.insert(entity);
                            }
                        }
                        if (attacmentEntityList != null){
                            for (Entity entity : attacmentEntityList){
                                session.insert(entity);
                            }
                        }
                        session.commit();
                        log.info("邮件：{} 插入成功------------- 计数：{}/{}", mailName, count.incrementAndGet(), dataSize);// 并发下的 ++i 操作
                    } catch (SQLException e) {
                        e.printStackTrace();
                        session.quietRollback();
                        log.error("邮件：{} 插入失败......", mailPath);// 要么全部插入成功，要么全部插入失败
                        unInsert.append(mailPath+"\n");
                    }
                } catch (Exception e) {
                    unInsert.append(mailPath+"\n");
                    log.error("解析失败: {}",mailPath);
                }finally {
                    latch.countDown();//线程执行完一个，计数减一
                }
            });
        };

        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        pool.shutdown();

        long end = System.currentTimeMillis();


        if (unInsert.length() > 0){
            try {
                FileWriter writer = new FileWriter("./unInsert.txt");
                writer.write(unInsert.toString());
                writer.flush();
                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        String disc = new SimpleDateFormat("HH:mm:ss").format(end-begin);

        log.info("------邮件个数：{}，插入成功个数：{}，插入失败个数：{},耗时（减 8 小时才正确）：{} ------",dataSize,count.intValue(),dataSize-count.intValue(),disc);


    }

}