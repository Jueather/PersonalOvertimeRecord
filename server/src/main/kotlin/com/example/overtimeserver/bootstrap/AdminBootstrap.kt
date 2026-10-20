package com.example.overtimeserver.bootstrap

import com.example.overtimeserver.auth.PasswordHasher
import com.example.overtimeserver.db.UserDao
import org.slf4j.LoggerFactory

/**
 * 首启引导：users 表为空时创建管理员。
 * 密码来源优先级：
 * 1. 环境变量 ADMIN_INITIAL_PASSWORD
 * 2. 同名系统属性 ADMIN_INITIAL_PASSWORD（本地测试/验证用）
 * 3. SecureRandom 生成并横幅打印（仅显示一次）
 * 幂等：已有任何用户即跳过。
 */
object AdminBootstrap {

    private val logger = LoggerFactory.getLogger("AdminBootstrap")

    /** 返回本次创建的明文密码；已有用户时返回 null */
    fun ensureAdmin(userDao: UserDao, adminUsername: String): String? {
        if (userDao.count() > 0) return null
        val password = System.getenv("ADMIN_INITIAL_PASSWORD")
            ?: System.getProperty("ADMIN_INITIAL_PASSWORD")
            ?: PasswordHasher.generatePassword()
        val created = userDao.create(adminUsername, PasswordHasher.hash(password), "管理员", "admin")
            ?: error("管理员创建失败：用户名 '$adminUsername' 已存在")
        logger.info("")
        logger.info("======================================================")
        logger.info("  首次启动：已创建管理员账号")
        logger.info("  用户名：{}", created.username)
        logger.info("  密码：{}", password)
        logger.info("  （仅显示这一次，请立即登录并修改密码）")
        logger.info("======================================================")
        logger.info("")
        return password
    }
}
