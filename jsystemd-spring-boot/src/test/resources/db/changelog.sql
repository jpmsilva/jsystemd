--liquibase formatted sql

--changeset jsystemd:create-users
CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(100));

--changeset jsystemd:create-orders
CREATE TABLE orders (id INT PRIMARY KEY, user_id INT);

--changeset jsystemd:index-orders
CREATE INDEX orders_user_id ON orders (user_id);
