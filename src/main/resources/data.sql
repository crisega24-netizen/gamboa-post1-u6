INSERT INTO productos (id, precio) VALUES (1, 50000);
INSERT INTO productos (id, precio) VALUES (2, 100000);
INSERT INTO productos (id, precio) VALUES (3, 20000);

INSERT INTO inventario (producto_id, stock) VALUES (1, 5);
INSERT INTO inventario (producto_id, stock) VALUES (2, 100);
INSERT INTO inventario (producto_id, stock) VALUES (3, 50);

INSERT INTO clientes (id, tipo_cliente, nit) VALUES (1, 'ESTANDAR', NULL);
INSERT INTO clientes (id, tipo_cliente, nit) VALUES (2, 'VIP', NULL);
INSERT INTO clientes (id, tipo_cliente, nit) VALUES (3, 'FRECUENTE', NULL);
INSERT INTO clientes (id, tipo_cliente, nit) VALUES (4, 'MOROSO', NULL);

INSERT INTO facturas (id, cliente_id, monto, pagada) VALUES (1, 4, 150000, FALSE);

-- Historial de 11 pedidos previos para el cliente 3, para activar el tramo >10 del descuento FRECUENTE
INSERT INTO pedidos (cliente_id, subtotal, descuento, impuesto, total, fecha, estado)
SELECT 3, 10000, 0, 1900, 11900, CURRENT_TIMESTAMP, 'CONFIRMADO'
FROM SYSTEM_RANGE(1, 11);