package com.rechang.api.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rechang.api.entity.OrderEntity;
import com.rechang.api.mapper.OrderMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 乐观锁真实链路集成测试（票 #30004，验收标准：并发双更新同一订单仅一方成功）。
 * 不起 Spring 容器：H2(MySQL 模式) + 真实 MybatisPlusInterceptor(OptimisticLockerInnerInterceptor) + OrderMapper，
 * 验证 @Version 生效——同版本的两次更新先者成功且 version 自增、后者 0 行失败。
 */
class OrderOptimisticLockIntTest {

    private static HikariDataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void init() throws Exception {
        dataSource = new HikariDataSource(new HikariConfig() {{
            setJdbcUrl("jdbc:h2:mem:optlock;MODE=MySQL;DB_CLOSE_DELAY=-1");
            // 并发会话模拟两个事务，池子须容纳同时打开的连接
            setMaximumPoolSize(4);
        }});
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE `order` (
                        id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
                        order_no              VARCHAR(32)  NOT NULL,
                        user_id               BIGINT       NOT NULL,
                        performance_id        BIGINT,
                        total_amount          INT,
                        refunded_amount       INT,
                        pay_channel           VARCHAR(32),
                        source                VARCHAR(32),
                        original_order_id     BIGINT,
                        original_pay_order_id BIGINT,
                        status                VARCHAR(16)  NOT NULL,
                        version               INT          NOT NULL DEFAULT 0,
                        completed_at          TIMESTAMP,
                        paid_at               TIMESTAMP,
                        refunded_at           TIMESTAMP,
                        cancelled_at          TIMESTAMP,
                        cancel_reason         VARCHAR(64),
                        reviewed_at           TIMESTAMP,
                        transferred_at        TIMESTAMP,
                        create_time           TIMESTAMP,
                        update_time           TIMESTAMP
                    )""");
        }

        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setLogImpl(NoLoggingImpl.class);
        configuration.addInterceptor(interceptor);
        configuration.setEnvironment(new Environment("optlock-test", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(OrderMapper.class);

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        sqlSessionFactory = factoryBean.getObject();
    }

    @AfterAll
    static void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private static OrderEntity newOrder(String status) {
        OrderEntity o = new OrderEntity();
        o.setOrderNo("NO-" + System.nanoTime());
        o.setUserId(1L);
        o.setStatus(status);
        o.setVersion(0);
        o.setTotalAmount(10000);
        return o;
    }

    @Test
    @DisplayName("同一起点的两次更新：先者成功 1 行且 version 自增，后者 0 行失败（并发双更新仅一方成功）")
    void staleVersionUpdateLoses() {
        // insert 在独立会话完成
        Long orderId;
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OrderMapper mapper = session.getMapper(OrderMapper.class);
            OrderEntity order = newOrder("PENDING_PAY");
            mapper.insert(order);
            orderId = order.getId();
        }

        // 两个独立会话各自加载同一行（等价两个事务的同一 version 快照；同会话两次 select 会命中
        // 一级缓存返回同一实例，无法模拟并发——这也是本用例首次运行踩到的坑）
        OrderEntity payer;
        OrderEntity canceller;
        try (SqlSession sa = sqlSessionFactory.openSession(true);
             SqlSession sb = sqlSessionFactory.openSession(true)) {
            payer = sa.getMapper(OrderMapper.class).selectById(orderId);
            canceller = sb.getMapper(OrderMapper.class).selectById(orderId);
        }
        assertThat(payer.getVersion()).isEqualTo(0);
        assertThat(canceller.getVersion()).isEqualTo(0);

        payer.setStatus("ISSUED");
        canceller.setStatus("CANCELLED");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OrderMapper mapper = session.getMapper(OrderMapper.class);
            assertThat(mapper.updateById(payer)).isEqualTo(1);
            assertThat(mapper.updateById(canceller)).isZero();

            OrderEntity latest = mapper.selectById(orderId);
            assertThat(latest.getStatus()).isEqualTo("ISSUED");
            assertThat(latest.getVersion()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("新版本号再更新：以最新 version 提交仍成功（冲突后重读可继续）")
    void freshVersionUpdateWins() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OrderMapper mapper = session.getMapper(OrderMapper.class);

            OrderEntity order = newOrder("ISSUED");
            mapper.insert(order);

            OrderEntity stale = mapper.selectById(order.getId());
            stale.setStatus("ATTENDED");
            assertThat(mapper.updateById(stale)).isEqualTo(1);

            OrderEntity latest = mapper.selectById(order.getId());
            latest.setStatus("REVIEWED");
            latest.setUpdateTime(new Date());
            assertThat(mapper.updateById(latest)).isEqualTo(1);
            assertThat(mapper.selectById(order.getId()).getVersion()).isEqualTo(2);
        }
    }
}
