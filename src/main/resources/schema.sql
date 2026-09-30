CREATE TABLE productos (
    id BIGINT PRIMARY KEY,
    precio DOUBLE NOT NULL
);

CREATE TABLE inventario (
    producto_id BIGINT PRIMARY KEY,
    stock INT NOT NULL
);

CREATE TABLE clientes (
    id BIGINT PRIMARY KEY,
    tipo_cliente VARCHAR(20) NOT NULL,
    nit VARCHAR(20)
);

CREATE TABLE facturas (
    id BIGINT PRIMARY KEY,
    cliente_id BIGINT NOT NULL,
    monto DOUBLE NOT NULL,
    pagada BOOLEAN NOT NULL
);

CREATE TABLE pedidos (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cliente_id BIGINT NOT NULL,
    subtotal DOUBLE NOT NULL,
    descuento DOUBLE NOT NULL,
    impuesto DOUBLE NOT NULL,
    total DOUBLE NOT NULL,
    fecha TIMESTAMP NOT NULL,
    estado VARCHAR(20) NOT NULL
);

CREATE TABLE detalle_pedido (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    pedido_id BIGINT NOT NULL,
    producto_id BIGINT NOT NULL,
    cantidad INT NOT NULL
);