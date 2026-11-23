CREATE OR REPLACE VIEW user_orders AS SELECT users.name, orders.id FROM users JOIN orders ON orders.user_id = users.id;
