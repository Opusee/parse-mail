package com.may;

import cn.hutool.db.Db;
import cn.hutool.db.Entity;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import com.may.utils.MailUtils;
import org.apache.commons.mail.util.MimeMessageUtils;

import javax.mail.internet.MimeMessage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class Download {

    static final Log log = LogFactory.get();

    static volatile AtomicInteger count = new AtomicInteger(0);
    static volatile AtomicInteger nofile = new AtomicInteger(0);
    static volatile AtomicInteger exfile = new AtomicInteger(0);
    static StringBuffer unInsert = new StringBuffer();

    public static void main(String[] args) {

        List<Entity> dataList = new ArrayList<>();
        try {
            dataList = Db.use().query("SELECT mailld,mail_path FROM mljr_mail_contents WHERE LOCATE('.eml',mail_path) > 0 AND mailld >= 520000 AND mailld < 650000");
        } catch (SQLException e) {
            e.printStackTrace();
        }

        final int dataSize = dataList.size();
        CountDownLatch latch = new CountDownLatch(dataSize);
        long begin = System.currentTimeMillis();

        //创建线程池
        ExecutorService pool = Executors.newFixedThreadPool(13);

        dataList.stream().forEach((data) -> {

            pool.execute(() -> {
                MailUtils mailUtils = MailUtils.create();
                String mailPath = data.getStr("mail_path");
                String[] split = mailPath.split("[/]+");
                int len = split.length;
                // 文件名带上一层目录，才是唯一的
                String mailName = split[len - 2] + "/" + split[len - 1].replaceAll(".eml", "");

                try {
                    MimeMessage mimeMessage = MimeMessageUtils.createMimeMessage(null, new File(mailPath));
                    // 附件信息
                    if (mailUtils.isContainAttachment(mimeMessage)) {
                        // 存放附件路径
                        List<Map<String, String>> attachmentList = new ArrayList<>();
                        // 下载没有另开线程，解析速度会有一定影响
                        // 下载路径例： /mnt/db/mail/attachment/administrator_mljr.com/ZA0729fv9UO5UtTh6TRSZ_vJe0Bg8n/附件xx
                        mailUtils.saveAttachment(mimeMessage, "/mnt/db/mail/attachment/" + mailName + "/", attachmentList);

                        log.info("邮件：{} 下载成功------------- 计数：{}/{}", mailPath, count.incrementAndGet(), dataSize);// 并发下的 ++i 操作
                    }else {
                        log.info("邮件：{} 无附件，计数目前有：{} 个", mailPath,nofile.incrementAndGet());// 并发下的 ++i 操作
                    }
                } catch (Exception e) {
                    log.error("邮件：{} 解析出路径无文件，计数目前有：{} 个",mailPath,exfile.incrementAndGet());
                    unInsert.append(mailPath + "\n");
                } finally {
                    latch.countDown();//线程执行完一个，计数减一
                }
            });

        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        pool.shutdown();

        long end = System.currentTimeMillis();

        if (unInsert.length() > 0) {
            try {
                FileWriter writer = new FileWriter("./undownload.txt");
                writer.write(unInsert.toString());
                writer.flush();
                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        String disc = new SimpleDateFormat("HH:mm:ss").format(end-begin);
        log.info("------邮件个数：{}，下载成功个数：{}，无附件个数：{}，有路径无文件的个数：{},耗时（减 8 小时才正确）：{} ------", dataSize, count.intValue(), nofile.intValue(),exfile.intValue(), disc);


    }

}