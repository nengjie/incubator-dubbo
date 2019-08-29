package org.apache.dubbo.test;

import org.junit.Test;
import java.util.ServiceLoader;

/**
 * @Description
 * @Date 2019/8/30 1:17
 * @Author nengjie
 */
public class JavaSPITest {

    @Test
    public void sayHello() throws Exception {
        ServiceLoader<Robot> serviceLoader = ServiceLoader.load(Robot.class);
        System.out.println("Java SPI");
        serviceLoader.forEach(Robot::sayHello);
    }
}
