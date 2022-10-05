from typing import Any, Callable, Iterable, TypeVar, Generic, Sequence, List, Optional, Union
from torch.utils.data.dataloader import default_collate
import torch.utils.data as torchData
from torch.utils.data import (
    IterDataPipe,
    MapDataPipe,
    SequentialSampler,
    Sampler,
    RandomSampler,
    BatchSampler, )
from torchvision import datasets
import numpy
from torchvision.transforms import ToTensor

# IterableDataset = NewType("IterableDataset", torchData.IterableDataset)
T_co = TypeVar('T_co', covariant=True)
T = TypeVar('T')
_worker_init_fn_t = Callable[[int], None]
_collate_fn_t = Callable[[List[T]], Any]

__all__ = [
    "Dataset",
    "IterableDataset",
    "ConcatDataset",
    'DataLoader',
    'ToNumpy',
]


class ToNumpy:

    def __init__(self) -> None:
        self.transform = ToTensor()

    def __call__(self, pic):
        t = self.transform(pic)
        return t.cpu().detach().numpy()

    def __repr__(self) -> str:
        return f"{self.__class__.__name__}()"


def collate_fn_tonumpy_usingtensor(data):
    # lets use default instead of doing it manually
    # then convert it to numpy
    tensor_list = default_collate(data)

    return [x.cpu().detach().numpy() for x in tensor_list]


class Dataset(torchData.Dataset[T_co]):
    pass


class IterableDataset(torchData.IterableDataset[T_co]):
    pass


class ConcatDataset(torchData.ConcatDataset[T_co]):
    pass


class DataLoader(torchData.DataLoader):
    def __init__(self, dataset: Dataset[T_co], batch_size: Optional[int] = 1,
                 shuffle: Optional[bool] = None, sampler: Union[Sampler, Iterable, None] = None,
                 batch_sampler: Union[Sampler[Sequence], Iterable[Sequence], None] = None,
                 num_workers: int = 0, collate_fn: Optional[_collate_fn_t] = None,
                 pin_memory: bool = False, drop_last: bool = False,
                 timeout: float = 0, worker_init_fn: Optional[_worker_init_fn_t] = None,
                 multiprocessing_context=None, generator=None,
                 *, prefetch_factor: int = 2,
                 persistent_workers: bool = False,
                 pin_memory_device: str = ""):
        collate = collate_fn_tonumpy_usingtensor if collate_fn is None else collate_fn
        super().__init__(dataset=dataset, batch_size=batch_size, shuffle=shuffle, sampler=sampler,
                         batch_sampler=batch_sampler, \
                         num_workers=num_workers, collate_fn=collate, pin_memory=pin_memory, drop_last=drop_last,
                         timeout=timeout, \
                         worker_init_fn=worker_init_fn, multiprocessing_context=multiprocessing_context, \
                         generator=generator, prefetch_factor=prefetch_factor, persistent_workers=persistent_workers,
                         pin_memory_device=pin_memory_device)
