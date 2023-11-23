use super::common::{Hypervisor, HypervisorCap};
use crate::error::Result;
use crate::util::SIZE_4KB;
use uuid::{uuid, Uuid};

pub(super) struct GunyahHypervisor;

impl GunyahHypervisor {
    pub const UUID: Uuid = uuid!("c1d58fcd-a453-5fdb-9265-ce36673d5f14");
}

impl Hypervisor for GunyahHypervisor {
    fn mmio_guard_init(&self) -> Result<()> {
        Ok(())
    }

    fn mmio_guard_map(&self, _addr: usize) -> Result<()> {
        Ok(())
    }

    fn mmio_guard_unmap(&self, _addr: usize) -> Result<()> {
        Ok(())
    }

    fn mem_share(&self, _base_ipa: u64) -> Result<()> {
        unimplemented!();
    }

    fn mem_unshare(&self, _base_ipa: u64) -> Result<()> {
        unimplemented!();
    }

    fn memory_protection_granule(&self) -> Result<usize> {
        Ok(SIZE_4KB)
    }

    fn has_cap(&self, _cap: HypervisorCap) -> bool {
        false
    }
}
