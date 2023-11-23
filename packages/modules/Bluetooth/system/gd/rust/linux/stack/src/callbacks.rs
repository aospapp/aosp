//! Provides utilities for managing callbacks.

use std::collections::HashMap;
use tokio::sync::mpsc::Sender;

use crate::{Message, RPCProxy};

/// Utility for managing callbacks conveniently.
pub struct Callbacks<T: Send + ?Sized> {
    callbacks: HashMap<u32, Box<T>>,
    tx: Sender<Message>,
    disconnected_message: fn(u32) -> Message,
}

impl<T: RPCProxy + Send + ?Sized> Callbacks<T> {
    /// Creates new Callbacks.
    ///
    /// Parameters:
    /// `tx`: Sender to use when notifying callback disconnect events.
    /// `disconnected_message`: Constructor of the message to be sent on callback disconnection.
    pub fn new(tx: Sender<Message>, disconnected_message: fn(u32) -> Message) -> Self {
        Self { callbacks: HashMap::new(), tx, disconnected_message }
    }

    /// Stores a new callback and monitors for callback disconnect.
    ///
    /// When the callback disconnects, a message is sent. This message should be handled and then
    /// the `remove_callback` function can be used.
    ///
    /// Returns the id of the callback.
    pub fn add_callback(&mut self, mut callback: Box<T>) -> u32 {
        let tx = self.tx.clone();
        let disconnected_message = self.disconnected_message;
        let id = callback.register_disconnect(Box::new(move |cb_id| {
            let tx = tx.clone();
            tokio::spawn(async move {
                let _result = tx.send(disconnected_message(cb_id)).await;
            });
        }));

        self.callbacks.insert(id, callback);
        id
    }

    /// Removes the callback given the id.
    ///
    /// When a callback is removed, disconnect monitoring is stopped and the proxy object is
    /// removed.
    ///
    /// Returns true if callback is removed, false if there is no such id.
    pub fn remove_callback(&mut self, id: u32) -> bool {
        match self.callbacks.get_mut(&id) {
            Some(callback) => {
                // Stop watching for disconnect.
                callback.unregister(id);
                // Remove the proxy object.
                self.callbacks.remove(&id);
                true
            }
            None => false,
        }
    }

    /// Returns the callback object based on the given id.
    pub fn get_by_id(&self, id: u32) -> Option<&Box<T>> {
        self.callbacks.get(&id)
    }

    /// Returns the mut callback object based on the given id.
    pub fn get_by_id_mut(&mut self, id: u32) -> Option<&mut Box<T>> {
        self.callbacks.get_mut(&id)
    }

    /// Applies the given function on all active callbacks.
    pub fn for_all_callbacks<F: Fn(&mut Box<T>)>(&mut self, f: F) {
        for (_, ref mut callback) in self.callbacks.iter_mut() {
            f(callback);
        }
    }
}
