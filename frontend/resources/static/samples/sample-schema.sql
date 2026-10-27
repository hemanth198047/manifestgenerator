-- Synthetic sample DDL (Oracle style)
CREATE TABLE trade.orders (
  order_id   NUMBER(12)   NOT NULL,
  client_id  VARCHAR2(20),
  trade_qty  NUMBER(10,2),
  created_at TIMESTAMP,
  CONSTRAINT pk_orders PRIMARY KEY (order_id)
);

ALTER TABLE trade.orders ADD (status VARCHAR2(10));

COMMENT ON TABLE trade.orders IS 'Customer trade orders';
COMMENT ON COLUMN trade.orders.trade_qty IS 'Number of units traded';
COMMENT ON COLUMN trade.orders.client_id IS 'Client identifier';

CREATE VIEW trade.v_orders AS
  SELECT order_id, trade_qty AS qty FROM trade.orders;
