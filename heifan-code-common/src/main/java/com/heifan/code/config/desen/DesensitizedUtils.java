package com.heifan.code.config.desen;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

public class DesensitizedUtils {
    /**
     * 获取脱敏json串(递归引用会导致java.lang.StackOverflowError)
     *
     * @param javaBean
     * @return
     */
    public static String getJson(Object javaBean) {
        String json = null;
        if (null != javaBean) {
            try {
                if (javaBean.getClass().isInterface()) {
                    return json;
                }
                /* 克隆出一个实体进行字段修改，避免修改原实体 */
//                Object clone = DesensitizedObjectUtils.deepClone(javaBean);
                Object clone = DesensitizedObjectUtils.deepCloneObject(javaBean);
                /* 定义一个计数器，用于避免重复循环自定义对象类型的字段 */
                Set<Integer> referenceCounter = new HashSet<>();
                /* 对克隆实体进行脱敏操作 */
                DesensitizedUtils.replace(DesensitizedObjectUtils.getAllFields(clone), clone, referenceCounter);
                /* 利用fastjson对脱敏后的克隆对象进行序列化 */
                json = JSON.toJSONString(clone, SerializerFeature.WriteMapNullValue, SerializerFeature.WriteNullListAsEmpty);
                /* 清空计数器 */
                referenceCounter.clear();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        return json;
    }

    public static <T> T getObj(T javaBean) {
        T clone = null;
        if (null != javaBean) {
            try {
                if (javaBean.getClass().isInterface()) {
                    return null;
                }
                //克隆出一个实体进行字段修改，避免修改原实体
//                clone = (T) DesensitizedObjectUtils.deepClone(javaBean);
                clone = DesensitizedObjectUtils.deepCloneObject(javaBean);
                //定义一个计数器，用于避免重复循环自定义对象类型的字段
                Set<Integer> referenceCounter = new HashSet<Integer>();
                //对克隆实体进行脱敏操作
                DesensitizedUtils.replace(DesensitizedObjectUtils.getAllFields(clone), clone, referenceCounter);
                //清空计数器
                referenceCounter.clear();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        return clone;
    }

    /**
     * 对需要脱敏的字段进行转化
     *
     * @param fields
     * @param javaBean
     * @param referenceCounter
     * @throws IllegalArgumentException
     * @throws IllegalAccessException
     */
    private static void replace(Field[] fields, Object javaBean, Set<Integer> referenceCounter) throws IllegalArgumentException, IllegalAccessException {
        if (null != fields && fields.length > 0) {
            for (Field field : fields) {
                field.setAccessible(true);
                if (null != javaBean) {
                    Object value = field.get(javaBean);
                    if (null != value) {
                        Class<?> type = value.getClass();
                        //处理子属性，包括集合中的
                        if (type.isArray()) {
                            //对数组类型的字段进行递归过滤
                            int len = Array.getLength(value);
                            for (int i = 0; i < len; i++) {
                                Object arrayObject = Array.get(value, i);
                                if (isNotGeneralType(arrayObject.getClass(), arrayObject, referenceCounter)) {
                                    replace(DesensitizedObjectUtils.getAllFields(arrayObject), arrayObject, referenceCounter);
                                }
                            }
                        } else if (value instanceof Collection<?>) {
                            //对集合类型的字段进行递归过滤
                            Collection<?> c = (Collection<?>) value;
                            for (Object collectionObj : c) {
                                // TODO: 待优化
                                if (isNotGeneralType(collectionObj.getClass(), collectionObj, referenceCounter)) {
                                    replace(DesensitizedObjectUtils.getAllFields(collectionObj), collectionObj, referenceCounter);
                                }
                            }
                        } else if (value instanceof Map<?, ?>) {
                            //对Map类型的字段进行递归过滤
                            Map<?, ?> m = (Map<?, ?>) value;
                            Set<?> set = m.entrySet();
                            for (Object o : set) {
                                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) o;
                                Object mapVal = entry.getValue();
                                if (isNotGeneralType(mapVal.getClass(), mapVal, referenceCounter)) {
                                    replace(DesensitizedObjectUtils.getAllFields(mapVal), mapVal, referenceCounter);
                                }
                            }
                        } else if (value instanceof Enum<?>) {
                            continue;
                        } else {
                            /*除基础类型、jdk类型的字段之外，对其他类型的字段进行递归过滤*/
                            if (!type.isPrimitive()
                                    && type.getPackage() != null
                                    && !StringUtils.startsWith(type.getPackage().getName(), "javax.")
                                    && !StringUtils.startsWith(type.getPackage().getName(), "java.")
                                    && !StringUtils.startsWith(field.getType().getName(), "javax.")
                                    && !StringUtils.startsWith(field.getName(), "java.")
                                    && referenceCounter.add(value.hashCode())) {
                                replace(DesensitizedObjectUtils.getAllFields(value), value, referenceCounter);
                            }
                        }
                    }
                    //脱敏操作
                    setNewValueForField(javaBean, field, value);

                }
            }
        }
    }

    /**
     * 排除基础类型、jdk类型、枚举类型的字段
     *
     * @param clazz
     * @param value
     * @param referenceCounter
     * @return
     */
    private static boolean isNotGeneralType(Class<?> clazz, Object value, Set<Integer> referenceCounter) {
        return !clazz.isPrimitive()
                && clazz.getPackage() != null
                && !clazz.isEnum()
                && !StringUtils.startsWith(clazz.getPackage().getName(), "javax.")
                && !StringUtils.startsWith(clazz.getPackage().getName(), "java.")
                && !StringUtils.startsWith(clazz.getName(), "javax.")
                && !StringUtils.startsWith(clazz.getName(), "java.")
                && referenceCounter.add(value.hashCode());
    }

    /**
     * 脱敏操作（按照规则转化需要脱敏的字段并设置新值）
     * 目前只支持String类型的字段，如需要其他类型如BigDecimal、Date等类型，可以添加
     *
     * @param javaBean
     * @param field
     * @param value
     * @throws IllegalAccessException
     */
    public static void setNewValueForField(Object javaBean, Field field, Object value) throws IllegalAccessException {
        //处理自身的属性
        Desensitized annotation = field.getAnnotation(Desensitized.class);
        if (field.getType().equals(String.class) && null != annotation && executeIsEffectiveMethod(javaBean, annotation)) {
            String valueStr = (String) value;
            if (StringUtils.isNotBlank(valueStr)) {
                switch (annotation.type()) {
                    case CHINESE_NAME: {
                        field.set(javaBean, DesensitizedUtils.chineseName(valueStr));
                        break;
                    }
                    case ID_CARD: {
                        field.set(javaBean, DesensitizedUtils.idCardNum(valueStr));
                        break;
                    }
                    case FIXED_PHONE: {
                        field.set(javaBean, DesensitizedUtils.fixedPhone(valueStr));
                        break;
                    }
                    case MOBILE_PHONE: {
                        field.set(javaBean, DesensitizedUtils.mobilePhone(valueStr));
                        break;
                    }
                    case ADDRESS: {
                        field.set(javaBean, DesensitizedUtils.address(valueStr, 7));
                        break;
                    }
                    case EMAIL: {
                        field.set(javaBean, DesensitizedUtils.email(valueStr));
                        break;
                    }
                    case BANK_CARD: {
                        field.set(javaBean, DesensitizedUtils.bankCard(valueStr));
                        break;
                    }
                    case SECRET_NUM: {
                        field.set(javaBean, DesensitizedUtils.secretNum(valueStr));
                        break;
                    }
                    default: {
                        break;
                    }
                }
            }
        }
    }

    /**
     * 执行某个对象中指定的方法
     *
     * @param javaBean     对象
     * @param desensitized
     * @return
     */
    private static boolean executeIsEffectiveMethod(Object javaBean, Desensitized desensitized) {
        boolean isAnnotationEffictive = true;
        //注解默认生效
        if (desensitized != null) {
            String isEffictiveMethod = desensitized.isEffectiveMethod();
            if (isNotEmpty(isEffictiveMethod)) {
                try {
                    Method method = javaBean.getClass().getMethod(isEffictiveMethod);
                    method.setAccessible(true);
                    isAnnotationEffictive = (Boolean) method.invoke(javaBean);
                } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                    e.printStackTrace();
                }
            }
        }
        return isAnnotationEffictive;
    }

    private static boolean isNotEmpty(String str) {
        return str != null && !"".equals(str);
    }

    /**
     * 【中文姓名】只显示第一个汉字，其他隐藏为2个星号，比如：李**
     *
     * @param fullName
     * @return
     */
    public static String chineseName(String fullName) {
        if (StringUtils.isBlank(fullName)) {
            return "";
        }
        String name = StringUtils.left(fullName, 1);
        return StringUtils.rightPad(name, StringUtils.length(fullName), "*");
    }

    /**
     * 【身份证号】显示第一位和最后一位
     *
     * @param id
     * @return
     */
    public static String idCardNum(String id) {
        if (StringUtils.isBlank(id)) {
            return "";
        }
        return StringUtils.left(id, 1).concat(StringUtils.removeStart(StringUtils.leftPad(StringUtils.right(id, 1), StringUtils.length(id), "*"), "*"));
    }

    /**
     * 【虚拟账号】显示第一位和最后一位
     *
     * @param id
     * @return
     */
    public static String account(String id) {
        if (StringUtils.isBlank(id)) {
            return "";
        }
        return StringUtils.left(id, 1).concat(StringUtils.removeStart(StringUtils.leftPad(StringUtils.right(id, 1), StringUtils.length(id), "*"), "*"));
    }


    /**
     * 【固定电话 后四位，其他隐藏，比如1234
     *
     * @param num
     * @return
     */
    public static String fixedPhone(String num) {
        if (StringUtils.isBlank(num)) {
            return "";
        }
        return StringUtils.leftPad(StringUtils.right(num, 4), StringUtils.length(num), "*");
    }

    /**
     * 【手机号码】前三位，后四位，其他隐藏，比如135****6810
     *
     * @param num
     * @return
     */
    public static String mobilePhone(String num) {
        if (StringUtils.isBlank(num)) {
            return "";
        }
        return StringUtils.left(num, 3).concat(StringUtils.removeStart(StringUtils.leftPad(StringUtils.right(num, 4), StringUtils.length(num), "*"), "***"));
    }

    /**
     * 【地址】只显示到地区，不显示详细地址，比如：北京市海淀区****
     *
     * @param address
     * @param sensitiveSize 敏感信息长度
     * @return
     */
    public static String address(String address, int sensitiveSize) {
        if (StringUtils.isBlank(address)) {
            return "";
        }
        int length = StringUtils.length(address);
        return StringUtils.rightPad(StringUtils.left(address, length - sensitiveSize), length, "*");
    }

    /**
     * 【电子邮箱 邮箱前缀仅显示第一个字母，前缀其他隐藏，用星号代替，@及后面的地址显示，比如：d**@126.com>
     *
     * @param email
     * @return
     */
    public static String email(String email) {
        if (StringUtils.isBlank(email)) {
            return "";
        }
        int index = StringUtils.indexOf(email, "@");
        if (index <= 1) {
            return email;
        } else {
            return StringUtils.rightPad(StringUtils.left(email, 1), index, "*").concat(StringUtils.mid(email, index, StringUtils.length(email)));
        }
    }

    /**
     * 【银行卡号】前4位，后3位，其他用星号隐藏每位1个星号，比如：6217 **** **** **** 567>
     *
     * @param cardNum
     * @return
     */
    public static String bankCard(String cardNum) {
        if (StringUtils.isBlank(cardNum)) {
            return "";
        }
        return StringUtils.left(cardNum, 4).concat(StringUtils.removeStart(StringUtils.leftPad(StringUtils.right(cardNum, 3), StringUtils.length(cardNum), "*"), "****"));
    }

    /**
     * 【密码】密码的全部字符都用*代替，比如：******
     *
     * @param password
     * @return
     */
    public static String password(String password) {
        if (StringUtils.isBlank(password)) {
            return "";
        }
        String pwd = StringUtils.left(password, 0);
        return StringUtils.rightPad(pwd, StringUtils.length(password), "*");
    }


    /**
     * @param secretNum
     * @return java.lang.String
     * @description
     * @author HiF
     * @date 2023/3/6 9:40
     */
    public static String secretNum(String secretNum) {
        if (StringUtils.isBlank(secretNum)) {
            return "";
        }
        StringBuilder resStr = new StringBuilder();
        if (secretNum.contains(".")) {
            // 小数，10000.05->1****.05；0.56->*.56
            int indexOfPoint = secretNum.indexOf(".");
            if (1 < indexOfPoint) {
                resStr.append(secretNum.charAt(0))
                        .append(new StringBuilder(secretNum)
                                .replace(0, indexOfPoint, Objects.requireNonNull(getAsterisk(secretNum.substring(1, indexOfPoint)))));
            } else {
                resStr.append("*").append(secretNum.substring(indexOfPoint));
            }
        } else {
            // 整数（绝对值）
            if (3 < secretNum.length()) {
                resStr.append(getAsterisk(secretNum.substring(0, secretNum.length() - 1))).append(secretNum.charAt(secretNum.length() - 1));
            } else {
                resStr.append(secretNum.charAt(0)).append(getAsterisk(secretNum.substring(1, secretNum.length() - 1)));
            }
        }

        return resStr.toString();
    }

    private static String getAsterisk(String sourceStr) {
        if (StrUtil.isBlank(sourceStr)) {
            return null;
        }
        StringBuilder resStr = new StringBuilder();
        char[] chars = sourceStr.toCharArray();
        for (char c : chars) {
            resStr.append("*");
        }
        return resStr.toString();
    }

    /**
     * 遍历List脱敏数据
     *
     * @param content
     * @return
     */
    public static <T> List getList(List<T> content) {
        if (content == null || content.size() <= 0) {
            return content;
        }
        List list = new ArrayList<T>();
        for (T t : content) {
            list.add(getObj(t));
        }
        return list;
    }

}