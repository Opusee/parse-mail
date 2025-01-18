package com.may.utils;

import cn.hutool.extra.emoji.EmojiUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import org.apache.commons.mail.util.MimeMessageParser;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.MimeUtility;
import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;


public class MailUtils {

    static final Log log = LogFactory.get();

    public static MailUtils create(){
        return new MailUtils();
    }

    public MailUtils() {
    }

    /**
     * 获取发送日期
     * @param msg
     * @return
     * @throws MessagingException
     */
    public String getSentDate(MimeMessage msg) throws MessagingException {
        Date sentDate = msg.getSentDate();
        if (sentDate == null){
            return null;
        }else {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            ZoneId zoneId = ZoneId.systemDefault();
            LocalDateTime localDateTime = sentDate.toInstant().atZone(zoneId).toLocalDateTime();
//        LocalDateTime localDateTime = LocalDateTime.ofInstant(instant, zoneId);
            return fmt.format(localDateTime);
        }
    }


    /**
     * 根据收件人类型，获取邮件收件人、抄送和密送地址。如果收件人类型为空，则获得所有的收件人
     * <p>Message.RecipientType.TO  收件人</p>
     * <p>Message.RecipientType.CC  抄送</p>
     * <p>Message.RecipientType.BCC 密送</p>
     * @param msg 邮件内容
     * @param type 收件人类型
     * @return 收件人1 <邮件地址1>, 收件人2 <邮件地址2>, ...
     * @throws MessagingException
     */
    public List<InternetAddress> getReceiveAddress(MimeMessage msg, Message.RecipientType type) throws Exception{
        ArrayList<InternetAddress> receiveAddress = new ArrayList<>();
        Address[] addresss;
        if (type == null) {
            addresss = msg.getAllRecipients();
        } else {
            addresss = msg.getRecipients(type);
        }

        if (addresss == null || addresss.length < 1){
//            throw new MessagingException("没有收件人!");
            return null;
        }

        for (Address address : addresss) {
            InternetAddress internetAddress = (InternetAddress)address;
            receiveAddress.add(internetAddress);
        }
        return receiveAddress;
    }

    /**
     * 获得邮件发件人
     * @param msg 邮件内容
     * @return 姓名 <Email地址>
     * @throws MessagingException
     * @throws UnsupportedEncodingException
     */
    public InternetAddress getSendAddress(MimeMessage msg) throws MessagingException {
        Address[] froms = msg.getFrom();
        if (froms.length < 1){
//            throw new MessagingException("没有发件人!");
            return null;
        }
        return (InternetAddress)froms[0];
    }

    /**
     * 文本解码
     * @param encodeText 解码MimeUtility.encodeText(String text)方法编码后的文本
     * @return 解码后的文本
     * @throws UnsupportedEncodingException
     */
    public static String decodeText(String encodeText) throws UnsupportedEncodingException {
        if (encodeText == null || "".equals(encodeText)) {
            return "";
        } else {
            return MimeUtility.decodeText(encodeText);
        }
    }

    /**
     * 获得邮件主题
     * @param msg 邮件内容
     * @return 解码后的邮件主题
     */
    public String getSubject(MimeMessage msg) throws Exception {
        String subject = msg.getSubject();
        if (subject != null){
            String replaceAll = subject.replaceAll("[\\s]+", "");
            if (EmojiUtil.containsEmoji(replaceAll)){
                return EmojiUtil.removeAllEmojis(replaceAll);
            }
            return MimeUtility.decodeText(replaceAll);
        }
        return null;
    }

    /**
     * 判断邮件中是否包含附件
     * @return 邮件中存在附件返回true，不存在返回false
     * @throws MessagingException
     * @throws IOException
     */
    public boolean isContainAttachment(Part part) throws MessagingException, IOException {
        boolean flag = false;
        if (part.isMimeType("multipart/*")) {
            MimeMultipart multipart = (MimeMultipart) part.getContent();
            int partCount = multipart.getCount();
            for (int i = 0; i < partCount; i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                String disp = bodyPart.getDisposition();
                if (disp != null && (disp.equalsIgnoreCase(Part.ATTACHMENT) || disp.equalsIgnoreCase(Part.INLINE))) {
                    flag = true;
                } else if (bodyPart.isMimeType("multipart/*")) {
                    flag = isContainAttachment(bodyPart);
                } else {
                    String contentType = bodyPart.getContentType();
                    if (contentType.indexOf("application") != -1) {
                        flag = true;
                    }

                    if (contentType.indexOf("name") != -1) {
                        flag = true;
                    }
                }

                if (flag) break;
            }
        } else if (part.isMimeType("message/rfc822")) {
            flag = isContainAttachment((Part)part.getContent());
        }
        return flag;
    }

    /**
     * 保存附件
     * @param part 邮件中多个组合体中的其中一个组合体
     * @param destDir  附件保存目录
     * @throws UnsupportedEncodingException
     * @throws MessagingException
     * @throws FileNotFoundException
     * @throws IOException
     */
    public void saveAttachment(Part part, String destDir, List<Map<String,String>> attachmentList) throws Exception {

        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();    //复杂体邮件
            //复杂体邮件包含多个邮件体
            int partCount = multipart.getCount();
            for (int i = 0; i < partCount; i++) {
                //获得复杂体邮件中其中一个邮件体
                BodyPart bodyPart = multipart.getBodyPart(i);
                //某一个邮件体也有可能是由多个邮件体组成的复杂体
                String disp = bodyPart.getDisposition();
                // 表示附件内容
                if (disp != null && (disp.equalsIgnoreCase(Part.ATTACHMENT) || disp.equalsIgnoreCase(Part.INLINE))) {
                    InputStream is = bodyPart.getInputStream();
                    String fileName = bodyPart.getFileName();
                    fileName = decodeText(fileName);
//                    File dir = new File(destDir);
//                    if (!dir.exists()){
//                        dir.mkdirs();
//                    }
//                    saveFileBynio(is, destDir, decodeText(fileName)); // 这里才需要下载
                    // 保存文件路径
                    HashMap<String, String> map = new HashMap<>();
                    if (EmojiUtil.containsEmoji(fileName)){
                        fileName = EmojiUtil.removeAllEmojis(fileName);
                    }
                    map.put("fileName",fileName);
                    map.put("filePath",destDir + fileName);
                    attachmentList.add(map);
                } else if (bodyPart.isMimeType("multipart/*")) {
                    saveAttachment(bodyPart,destDir,attachmentList);
                } else {
                    String contentType = bodyPart.getContentType();
                    // 表示正文中的附件内容
                    if (contentType.indexOf("name") != -1 || contentType.indexOf("application") != -1) {
//                        saveFile(bodyPart.getInputStream(), destDir, decodeText(bodyPart.getFileName())); // 这里不下载
                    }
                }
            }
        } else if (part.isMimeType("message/rfc822")) {
            saveAttachment((Part) part.getContent(),destDir,attachmentList);
        }
    }

    /**
     * 读取输入流中的数据保存至指定目录
     * @param is 输入流
     * @param fileName 文件名
     * @param destDir 文件存储目录
     * @throws FileNotFoundException
     * @throws IOException
     */
    private void saveFile(InputStream is, String destDir, String fileName)
            throws FileNotFoundException, IOException {
        BufferedInputStream bis = new BufferedInputStream(is);
        BufferedOutputStream bos = new BufferedOutputStream(
                new FileOutputStream(new File(destDir + fileName)));
        int len = -1;
        while ((len = bis.read()) != -1) {
            bos.write(len);
            bos.flush();
        }
        bos.close();
        bis.close();
    }

    /**
     * jdk1.7 以后版本的拷贝
     * @param in
     * @param destDir
     * @param fileName
     */
    private void saveFile7(InputStream in,String destDir, String fileName){
        try {
            // 实际上就是对上面方法的封装，不定义读取缓存字节，默认是 8192 个字节
            Files.copy(in, Paths.get(destDir + fileName), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    /**
     * 基于 nio 零拷贝技术，减少上下文在内核模式和用户模式之间的切换，极大提高传输效率，需要 jdk1.8，推荐使用
     * @param in
     * @param destDir
     * @param fileName
     */
    private void saveFileBynio(InputStream in,String destDir, String fileName) throws Exception{
        ReadableByteChannel channelin = Channels.newChannel(in);
        FileOutputStream out = new FileOutputStream(new File(destDir + fileName));// 默认覆盖写入，如果要追加写入，构造器中传 true
        FileChannel channelout = out.getChannel();
        channelout.transferFrom(channelin,0,Long.MAX_VALUE);
    }

    /**
     * 获得邮件文本内容
     * @param part 邮件体
     * @param content 存储邮件文本内容的字符串
     * @throws MessagingException
     * @throws IOException
     */
    public void getMailTextContent(Part part, StringBuffer content) throws MessagingException, IOException {
        //如果是文本类型的附件，通过getContent方法可以取到文本内容，但这不是我们需要的结果，所以在这里要做判断
        boolean isContainTextAttach = part.getContentType().indexOf("name") > 0;
        if (part.isMimeType("text/*") && !isContainTextAttach) {
            content.append(part.getContent().toString());
        } else if (part.isMimeType("message/rfc822")) {
            getMailTextContent((Part)part.getContent(),content);
        } else if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            int partCount = multipart.getCount();
            for (int i = 0; i < partCount; i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                getMailTextContent(bodyPart,content);
            }
        }
    }

    /**
     *  是否含有邮件正文
     * @param mimeMessage
     * @return
     * 有的邮件用这个方法会报解码异常
     */
    public boolean hasContent(MimeMessage mimeMessage) throws Exception{
        MimeMessageParser parser = new MimeMessageParser(mimeMessage);
        return parser.parse().hasPlainContent();
    }

    /**
     * 获得邮件文本内容
     * @throws MessagingException
     * @throws IOException
     */
    public String getContent(MimeMessage mimeMessage) throws Exception {
        MimeMessageParser parser = new MimeMessageParser(mimeMessage);
        //parse.parse()方法返回的也是MimeMessageParser对象，不调用parse()方法就无法的到邮件内容，只能得到主题和收件人等信息

        try {
            String content = parser.parse().getPlainContent();
            if (content== null){
                return null;
            }
            content = content.replaceAll("[\\s]+", "");
            if (EmojiUtil.containsEmoji(content)){
//                    EmojiUtil.toAlias(content);//转义
                return EmojiUtil.removeAllEmojis(content);//移除
            }
            return content;
        } catch (Exception e) {
            // 有的邮件用 parser.parse().getPlainContent() 这种方式解析会解码出错
            StringBuffer buffer = new StringBuffer();
            getMailTextContent(mimeMessage,buffer);
            // 去除 html 标签及空格
            String str = buffer.toString().replaceAll("</?[^>]+>|[\\s]+", "");
            if (EmojiUtil.containsEmoji(str)){
                return EmojiUtil.removeAllEmojis(str);
            }
            return str;
        }
    }
}
