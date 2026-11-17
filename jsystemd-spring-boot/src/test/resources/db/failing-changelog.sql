--liquibase formatted sql

--changeset jsystemd:create-users
CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(100));

--changeset jsystemd:broken
CREATE TABLE users (id INT PRIMARY KEY);
