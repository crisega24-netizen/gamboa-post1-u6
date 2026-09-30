package com.tienda.pedidos.service;

import com.tienda.pedidos.dto.ItemPedido;
import com.tienda.pedidos.dto.PedidoRequest;
import com.tienda.pedidos.dto.ResultadoPedido;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.EmptyResultDataAccessException;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class GestorPedidosTest {

    @Autowired
    private GestorPedidos gestorPedidos;

    private PedidoRequest pedido(Long clienteId, String email, Long productoId, int cantidad) {
        PedidoRequest request = new PedidoRequest();
        request.setClienteId(clienteId);
        request.setClienteEmail(email);
        ItemPedido item = new ItemPedido();
        item.setProductoId(productoId);
        item.setCantidad(cantidad);
        request.setItems(List.of(item));
        return request;
    }

    @Test
    void rechazaPorStockInsuficiente() {
        // Cliente 1 (ESTANDAR), producto 1 tiene stock=5, se piden 100
        ResultadoPedido resultado = gestorPedidos.procesarPedido(pedido(1L, "ana@correo.com", 1L, 100));
        System.out.println("[Stock insuficiente] " + resultado.getMotivoRechazo());
        assertFalse(resultado.isConfirmado());
        assertTrue(resultado.getMotivoRechazo().contains("Stock insuficiente"));
    }

    @Test
    void rechazaPorClienteInexistente() {
        // NOTA DE DIAGNOSTICO: el codigo de GestorPedidos asume que
        // jdbcTemplate.queryForObject(...) devuelve null cuando no hay filas,
        // pero el comportamiento real y documentado de Spring JDBC es lanzar
        // EmptyResultDataAccessException en ese caso. La rama "if (tipoCliente
        // == null)" nunca se alcanza: un cliente inexistente no se rechaza
        // limpiamente, provoca una excepcion no controlada. Se documenta este
        // hallazgo en el README en lugar de corregirlo en este paso.
        assertThrows(
                EmptyResultDataAccessException.class,
                () -> gestorPedidos.procesarPedido(pedido(999L, "nadie@correo.com", 2L, 1))
        );
    }

    @Test
    void evaluaClienteMorosoSegunHorarioDeCorte() {
        // Cliente 4 (MOROSO) con deuda pendiente de $150.000 sin pagar.
        // El propio codigo depende del reloj real del sistema (LocalTime.now()),
        // asi que el resultado esperado se determina en el mismo instante de la prueba.
        boolean fueraDeHorario = !LocalTime.now().isBefore(LocalTime.of(20, 0));
        ResultadoPedido resultado = gestorPedidos.procesarPedido(pedido(4L, "moroso@correo.com", 2L, 1));
        System.out.println("[Cliente moroso, fueraDeHorario=" + fueraDeHorario + "] confirmado=" + resultado.isConfirmado());
        if (fueraDeHorario) {
            assertTrue(resultado.isConfirmado());
        } else {
            assertFalse(resultado.isConfirmado());
            assertTrue(resultado.getMotivoRechazo().contains("deuda pendiente"));
        }
    }

    @Test
    void aplicaDescuentoVip() {
        // Cliente 2 (VIP), 6 unidades de producto 2 ($100.000 c/u) = subtotal $600.000 -> tramo 10%
        ResultadoPedido resultado = gestorPedidos.procesarPedido(pedido(2L, "vip@correo.com", 2L, 6));
        System.out.println("[Descuento VIP] confirmado=" + resultado.isConfirmado() + " total=" + resultado.getTotal());
        assertTrue(resultado.isConfirmado());
        assertEquals(642600.0, resultado.getTotal(), 0.01);
    }

    @Test
    void aplicaDescuentoFrecuente() {
        // Cliente 3 (FRECUENTE) con 11 pedidos previos sembrados -> descuento 8%
        ResultadoPedido resultado = gestorPedidos.procesarPedido(pedido(3L, "frecuente@correo.com", 3L, 5));
        System.out.println("[Descuento FRECUENTE] confirmado=" + resultado.isConfirmado() + " total=" + resultado.getTotal());
        assertTrue(resultado.isConfirmado());
        assertEquals(109480.0, resultado.getTotal(), 0.01);
    }
}